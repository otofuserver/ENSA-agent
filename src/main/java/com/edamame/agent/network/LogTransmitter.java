package com.edamame.agent.network;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.log.LogEntry;
import com.edamame.agent.util.AgentLogger;
import com.edamame.agent.util.AgentVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import static com.edamame.agent.network.TcpProtocolConstants.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ログ転送クラス（TCP通信版）
 * 収集したログを枝豆コンテナにTCP通信で送信する
 * 接続を可能な限り使い回し、Socket timeoutを防止する
 * v2.4.0: 接続断絶時の自動再接続機能とログキューイング機能を追加
 *
 * @author Edamame Team
 * @version 2.4.0
 */
public class LogTransmitter {

    private static final String EMPTY_BLOCK_REQUESTS_JSON = "{\"requests\":[]}";

    private final AgentConfig config;
    private final ObjectMapper objectMapper;
    private final ReentrantLock connectionLock = new ReentrantLock(); // 排他制御用ロック
    
    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private boolean connected = false;
    private boolean authenticated = false;
    private long lastActivityTime = 0;
    private static final long CONNECTION_TIMEOUT = 300000; // 5分間の非活動でタイムアウト
    private static final int MAX_RETRY_ATTEMPTS = 3;

    // 再接続機能用の変数
    private volatile boolean reconnecting = false;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reconnectTask = null;
    private static final int RECONNECT_INTERVAL_SECONDS = 30; // 30秒間隔で再接続試行

    // ログキューイング機能用の変数
    private final Queue<LogEntry> logQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_QUEUE_SIZE = 10000; // 最大キューサイズ
    private static final int SHARED_SEND_BATCH_SIZE = 20; // 通常送信/再接続送信で共通の小分け粒度
    private static final long RECONNECT_DRAIN_DELAY_MILLIS = 300L; // バッチ間ウェイト

    /**
     * コンストラクタ
     *
     * @param config エージェント設定
     */
    public LogTransmitter(AgentConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        // LocalDateTimeの処理設定
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        AgentLogger.debug("TCP LogTransmitterを初期化しました: " +
            config.getEdamameHost() + ":" + config.getEdamamePort());
    }

    /**
     * 永続的な接続を確立・維持
     */
    private boolean ensureConnection() {
        connectionLock.lock(); // ロック取得
        try {
            // 他のスレッドが既に接続を確立している可能性をチェック
            if (connected && isConnectionAlive()) {
                updateLastActivity();
                AgentLogger.debug("既存の接続を再利用します");
                return true;
            }

            // 再接続モード中は、新規接続試行のみ抑止する（既存接続の再利用は許可）
            if (reconnecting) {
                AgentLogger.debug("再接続モード中のため新規接続をスキップします");
                return false;
            }

            // 接続が切れている場合は再接続
            if (connected) {
                AgentLogger.info("接続が切断されました。再接続を試行します");
                handleConnectionLoss();
                return false;
            }

            // 重複接続防止：既に別スレッドが接続処理中の場合は待機
            if (socket != null && socket.isConnected() && !socket.isClosed()) {
                AgentLogger.debug("別スレッドが接続処理中です。既存接続を使用します");
                connected = true;
                authenticated = true;
                updateLastActivity();
                return true;
            }

            return connectAndAuthenticate();
        } finally {
            connectionLock.unlock(); // ロック解放
        }
    }

    /**
     * 接続が生きているかチェック
     */
    private boolean isConnectionAlive() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            return false;
        }

        // 長時間非活動の場合は切断扱い
        long inactiveTime = System.currentTimeMillis() - lastActivityTime;
        if (inactiveTime > CONNECTION_TIMEOUT) {
            AgentLogger.info("接続がタイムアウトしました。再接続が必要です");
            return false;
        }

        return true;
    }

    /**
     * 接続断絶を検知した際の処理
     */
    private void handleConnectionLoss() {
        AgentLogger.warn("サーバー接続が切断されました。再接続待機モードに移行します");

        // 接続状態をリセット
        disconnect();

        // 再接続モードに移行
        if (!reconnecting) {
            startReconnectMode();
        }
    }

    /**
     * 再接続モードを開始
     */
    private void startReconnectMode() {
        reconnecting = true;

        // 既存の再接続タスク��あれば停止
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel(false);
        }

        AgentLogger.info("再接続モードを開始します（" + RECONNECT_INTERVAL_SECONDS + "秒間隔で試行）");

        // 再接続タスクを開始
        reconnectTask = reconnectExecutor.scheduleWithFixedDelay(
            this::attemptReconnect,
            5, // 初回は5秒後
            RECONNECT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    /**
     * 再接続を試行
     */
    private void attemptReconnect() {
        connectionLock.lock();
        try {
            if (connected && isConnectionAlive()) {
                // 既に接続済みの場合は再接続モードを終了
                stopReconnectMode();
                return;
            }

            AgentLogger.info("サーバーへの再接続を試行中...");

            if (connectAndAuthenticate()) {
                String registrationId = registerServer();
                if (registrationId == null) {
                    AgentLogger.warn("再接続時のサーバー登録に失敗しました。セッションを破棄して再試行します");
                    disconnect();
                    return;
                }

                if (!verifyConnectionStabilityAfterRegistration()) {
                    AgentLogger.warn("再接続直後の通信安定性確認に失敗しました。セッションを破棄して再試行します");
                    disconnect();
                    return;
                }

                AgentLogger.info("サーバーへの再接続に成功しました");
                stopReconnectMode();
                notifyReconnectionSuccess(registrationId);

                // キュー��溜まったデータを送信
                processQueuedData();
            } else {
                AgentLogger.warn("再接続に失敗しました。" + RECONNECT_INTERVAL_SECONDS + "秒後に再試行します");
            }

        } catch (Exception e) {
            AgentLogger.warn("再接続試行中にエラーが発生しました: " + e.getMessage());
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * 再接続モードを停止
     */
    private void stopReconnectMode() {
        reconnecting = false;

        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }

        AgentLogger.info("再接続モードを終了しました");
    }

    /**
     * キューに溜まったデータを処理
     */
    private synchronized void processQueuedData() {
        AgentLogger.info("キューに溜まったデータの送信を開始します");

        // ログキューは大量時の負荷を抑えるため、小分けで段階的に送信する
        int drainBatchSize = resolveSendBatchSize();
        int totalSent = 0;

        while (!logQueue.isEmpty()) {
            List<LogEntry> batchLogs = new ArrayList<>();
            while (!logQueue.isEmpty() && batchLogs.size() < drainBatchSize) {
                LogEntry log = logQueue.poll();
                if (log != null) {
                    batchLogs.add(log);
                }
            }

            if (batchLogs.isEmpty()) {
                break;
            }

            try {
                if (transmitLogsInternal(batchLogs)) {
                    totalSent += batchLogs.size();
                    AgentLogger.info("再接続キュー送信: " + batchLogs.size() + " 件送信（累計 " + totalSent + " 件）");
                } else {
                    batchLogs.forEach(logQueue::offer);
                    AgentLogger.warn("再接続キュー送信に失敗しました。残キュー " + logQueue.size() + " 件");
                    break;
                }
            } catch (Exception e) {
                batchLogs.forEach(logQueue::offer);
                AgentLogger.warn("再接続キュー送信中にエラーが発生しました: " + e.getMessage());
                break;
            }

            if (!logQueue.isEmpty()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(RECONNECT_DRAIN_DELAY_MILLIS));
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    AgentLogger.warn("再接続キュー送信待機が中断されました");
                    break;
                }
            }
        }

        // 残りのキューサイズを報告
        if (!logQueue.isEmpty()) {
            AgentLogger.info("ログキューに " + logQueue.size() + " 件のデータが残っています");
        } else {
            AgentLogger.info("再接続キュー送信が完了しました（累計 " + totalSent + " 件）");
        }
    }

    // 再接続成功時のコールバック機能用変数
    private volatile Runnable reconnectionSuccessCallback = null;

    /**
     * 再接続成功時のコールバックを設定
     */
    public void setReconnectionSuccessCallback(Runnable callback) {
        this.reconnectionSuccessCallback = callback;
    }

    /**
     * 再接続成功を通知
     */
    private void notifyReconnectionSuccess(String newRegistrationId) {
        if (reconnectionSuccessCallback != null) {
            try {
                reconnectionSuccessCallback.run();
            } catch (Exception e) {
                AgentLogger.warn("再接続成功コールバックの実行中にエラーが発生しました: " + e.getMessage());
            }
        }
    }

    /**
     * ログをキューに追加
     */
    private void queueLogs(List<LogEntry> logs) {
        for (LogEntry log : logs) {
            if (logQueue.size() < MAX_QUEUE_SIZE) {
                logQueue.offer(log);
            } else {
                // キューが満杯の場合は古いデータを削除
                logQueue.poll();
                logQueue.offer(log);
                AgentLogger.warn("ログキューが満杯のため、古いデータを削除しました");
            }
        }
        AgentLogger.info(logs.size() + " 件のログをキューに追加しました（キューサイズ: " + logQueue.size() + "）");
    }

    /**
     * 接続確立と認証を実行
     */
    private boolean connectAndAuthenticate() {
        try {
            // TCP接続確立
            socket = new Socket();
            socket.setKeepAlive(true);
            socket.setSoTimeout(30000); // 30秒のタイムアウト
            socket.connect(new InetSocketAddress(config.getEdamameHost(), config.getEdamamePort()), 30000);

            // 最小限の接続安定化待機（100ms）
            try {
                Thread.sleep(100); // 100ms待機で接続の安定化
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AgentLogger.warn("接続安定化待機が中断されました");
                return false;
            }

            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            AgentLogger.info("TCP接続を確立しました: " + config.getEdamameHost() + ":" + config.getEdamamePort());

            // 認証実行
            if (authenticate()) {
                connected = true;
                authenticated = true;
                updateLastActivity();
                AgentLogger.debug("TCP認証に成功しました（接続を維持します）");
                return true;
            } else {
                disconnect();
                return false;
            }

        } catch (IOException e) {
            AgentLogger.error("TCP接続確立に失敗しました: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * 認証処理
     */
    private boolean authenticate() {
        try {
            // APIキーとエージェント名を準備
            String apiKey = config.getApiKey();
            String agentName = config.getAgentId();

            // データ長を計算（APIキー長 + APIキー + エージェント名長 + エージェント名）
            byte[] apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
            byte[] agentNameBytes = agentName.getBytes(StandardCharsets.UTF_8);
            int totalDataLength = 4 + apiKeyBytes.length + 4 + agentNameBytes.length; // 長さフィールド込み

            // 認証メッセージを送信
            out.writeByte(MSG_TYPE_AUTH);
            out.writeInt(totalDataLength); // 全データ長
            out.writeInt(apiKeyBytes.length); // APIキー長
            out.write(apiKeyBytes); // APIキー
            out.writeInt(agentNameBytes.length); // エージェント名長
            out.write(agentNameBytes); // エージェント名
            out.flush();

            // レスポンスを受信
            byte responseCode = in.readByte();
            if (responseCode == RESPONSE_SUCCESS) {
                return true;
            } else {
                AgentLogger.warn("TCP認証に失敗しました。レスポンスコード: " + responseCode);
                return false;
            }

        } catch (IOException e) {
            AgentLogger.error("認証処理中にエラーが発生しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * ログリストを枝豆コンテナに送信（接続使い回し版）
     */
    public synchronized boolean transmitLogs(List<LogEntry> logs) {
        if (logs.isEmpty()) {
            return true;
        }

        // 接続が切断されている場合はキューに追加
        if (reconnecting || !connected) {
            queueLogs(logs);
            return false; // 実際の送信は行われていないがエラーではない
        }

        // 通常の送信処理
        try {
            return transmitLogsInChunks(logs);
        } catch (Exception e) {
            AgentLogger.warn("ログ送信中にエラーが発生しました: " + e.getMessage());

            // 未送信分を保持
            queueLogs(logs);

            // 接続エラーの場合は再接続モードに移行
            if (isConnectionError(e)) {
                handleConnectionLoss();
            }

            return false;
        }
    }

    /**
     * ロ��送信の内部処���
     */
    private boolean transmitLogsInternal(List<LogEntry> logs) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                if (!ensureConnection()) {
                    AgentLogger.warn("接続確立に失敗しました (試行 " + attempt + "/" + MAX_RETRY_ATTEMPTS + ")");
                    continue;
                }

                // ログバッチを送信
                LogBatch batch = new LogBatch(logs, config.getAgentId());
                String jsonData = objectMapper.writeValueAsString(batch);

                out.writeByte(MSG_TYPE_LOG_BATCH);
                writeString(jsonData);
                out.flush();

                byte responseCode = in.readByte();
                if (responseCode == RESPONSE_SUCCESS) {
                    String responseMessage = readString();
                    updateLastActivity();
                    AgentLogger.debug("ログ送信成功: " + responseMessage);
                    return true;
                } else {
                    String errorMessage = readString();
                    AgentLogger.warn("TCP送信エラー (試行 " + attempt + "/" + MAX_RETRY_ATTEMPTS + "): " + errorMessage);

                    if (isRegistrationStateError(responseCode, errorMessage)) {
                        throw new IOException("Agent session is not registered: " + errorMessage);
                    }

                    // 認証エラーの場合は再認証
                    if (responseCode == RESPONSE_AUTH_FAILED) {
                        authenticated = false;
                        connected = false;
                    }
                }

            } catch (Exception e) {
                AgentLogger.warn("TCP送信エラー (試行 " + attempt + "/" + MAX_RETRY_ATTEMPTS + "): " + e.getMessage());

                // IOエラーの場合は接続をリセット
                if (isConnectionError(e)) {
                    connected = false;
                    authenticated = false;
                    if (attempt == MAX_RETRY_ATTEMPTS) {
                        throw e; // 最後の試行で失敗した場合は例外を再スロー
                    }
                }
            }
        }

        AgentLogger.error(MAX_RETRY_ATTEMPTS + "回の試行でログ送信に失敗しました");
        return false;
    }

    /**
     * ログを共通バッチ粒度で分割送信
     */
    private boolean transmitLogsInChunks(List<LogEntry> logs) throws Exception {
        int batchSize = resolveSendBatchSize();

        for (int start = 0; start < logs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, logs.size());
            List<LogEntry> chunk = new ArrayList<>(logs.subList(start, end));

            try {
                if (!transmitLogsInternal(chunk)) {
                    queueRemainingLogs(logs, start);
                    AgentLogger.warn("分割送信に失敗したため未送信 " + (logs.size() - start) + " 件をキューへ退避しました");
                    return false;
                }
            } catch (Exception e) {
                queueRemainingLogs(logs, start);
                AgentLogger.warn("分割送信中にエラーが発生したため未送信 " + (logs.size() - start) + " 件をキューへ退避しました: " + e.getMessage());
                throw e;
            }
        }
        return true;
    }

    /**
     * 送信失敗時に未送信分のみキューへ退避
     */
    private void queueRemainingLogs(List<LogEntry> logs, int startIndex) {
        if (startIndex < 0 || startIndex >= logs.size()) {
            return;
        }
        List<LogEntry> remainingLogs = new ArrayList<>(logs.subList(startIndex, logs.size()));
        queueLogs(remainingLogs);
    }

    /**
     * ハートビートを送信（接続使い回し版）
     */
    public synchronized boolean sendHeartbeat() {
        // 接続が切断されている場合はスキップ
        if (reconnecting || !connected) {
            AgentLogger.debug("再接続モード中のためハートビートをスキップします");
            return false;
        }

        try {
            if (!ensureConnection()) {
                return false;
            }

            return sendHeartbeatInternal();

        } catch (Exception e) {
            AgentLogger.warn("ハートビート送信中にエラーが発生しました: " + e.getMessage());

            // 接続エラーの場合は再接続モードに移行
            if (isConnectionError(e)) {
                handleConnectionLoss();
            }

            return false;
        }
    }

    /**
     * ハートビート送信の内部処理
     */
    private boolean sendHeartbeatInternal() throws IOException {
        // ハートビートメッセージを作成
        Map<String, Object> heartbeatData = new HashMap<>();
        heartbeatData.put("agentName", config.getAgentId()); // agentIdをagentNameに変更
        heartbeatData.put("timestamp", System.currentTimeMillis());
        heartbeatData.put("status", "active");

        String jsonData = objectMapper.writeValueAsString(heartbeatData);

        out.writeByte(MSG_TYPE_HEARTBEAT);
        writeString(jsonData);
        out.flush();

        byte responseCode = in.readByte();
        if (responseCode == RESPONSE_SUCCESS) {
            String responseMessage = readString();
            updateLastActivity();
            AgentLogger.debug("ハートビート送信成功: " + responseMessage);
            return true;
        } else {
            String errorMessage = readString();
            AgentLogger.warn("ハートビート送信に失敗しました。レスポンスコード: " + responseCode + ", エラー: " + errorMessage);
            if (isRegistrationStateError(responseCode, errorMessage)) {
                AgentLogger.warn("セッション登録状態が無効なため再接続へ移行します");
                handleConnectionLoss();
            }
            return false;
        }
    }

    /**
     * ブロック要求を取得
     */
    public synchronized String fetchBlockRequests() {
        // 接続が切断されている場合は空の結果を返す
        if (reconnecting || !connected) {
            AgentLogger.debug("再接続モード中のためブロック要求をスキップします");
            return emptyBlockRequestsJson();
        }

        try {
            if (!ensureConnection()) {
                return emptyBlockRequestsJson();
            }

            out.writeByte(MSG_TYPE_BLOCK_REQUEST);
            writeString(config.getAgentId());
            out.flush();

            byte responseCode = in.readByte();
            if (responseCode == RESPONSE_SUCCESS) {
                String responseData = readString();
                updateLastActivity();
                AgentLogger.debug("ブロック要求取得成功");
                return responseData;
            } else {
                String errorMessage = readString();
                AgentLogger.warn("ブロック要求取得に失敗しました。レスポンスコード: " + responseCode + ", エラー: " + errorMessage);
                if (isRegistrationStateError(responseCode, errorMessage)) {
                    AgentLogger.warn("セッション登録状態が無効なため再接続へ移行します");
                    handleConnectionLoss();
                }
                return emptyBlockRequestsJson();
            }

        } catch (Exception e) {
            AgentLogger.warn("ブロック要求取得中にエラーが発生しました: " + e.getMessage());

            // 接続エラーの場合は再接続モードに移行
            if (isConnectionError(e)) {
                handleConnectionLoss();
            }

            return emptyBlockRequestsJson();
        }
    }

    /**
     * ブロック要求の空レスポンス（新仕様）を返す。
     */
    private String emptyBlockRequestsJson() {
        return EMPTY_BLOCK_REQUESTS_JSON;
    }

    /**
     * ブロック要求の適用結果をサーバーへ送信する。
     * @param results 結果リスト
     * @return 送信成功時true
     */
    public synchronized boolean sendBlockResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return true;
        }

        if (reconnecting || !connected) {
            AgentLogger.debug("再接続モード中のためブロック結果送信をスキップします");
            return false;
        }

        try {
            if (!ensureConnection()) {
                return false;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("results", results);
            String jsonData = objectMapper.writeValueAsString(payload);

            out.writeByte(MSG_TYPE_BLOCK_RESULT);
            writeString(jsonData);
            out.flush();

            byte responseCode = in.readByte();
            if (responseCode == RESPONSE_SUCCESS) {
                readString(); // サーバー応答メッセージ
                updateLastActivity();
                AgentLogger.debug("ブロック結果ACK送信成功: " + results.size() + " 件");
                return true;
            }

            String errorMessage = readString();
            AgentLogger.warn("ブロック結果ACK送信失敗: " + errorMessage);
            if (isRegistrationStateError(responseCode, errorMessage)) {
                AgentLogger.warn("セッション登録状態が無効なため再接続へ移行します");
                handleConnectionLoss();
            }
            return false;
        } catch (Exception e) {
            AgentLogger.warn("ブロック結果ACK送信中に���ラーが発生しました: " + e.getMessage());
            if (isConnectionError(e)) {
                handleConnectionLoss();
            }
            return false;
        }
    }

    /**
     * サーバー登録処理
     */
    public synchronized String registerServer() {
        try {
            if (!ensureConnection()) {
                AgentLogger.error("サーバー登録用の接続確立に失敗しました");
                return null;
            }

            // APIキーとサーバー情報を準備
            String apiKey = config.getApiKey();

            // システム情報を適切に取得
            String hostname = getHostname();
            String osName = getOsName();
            String osVersion = getOsVersion();
            String agentIp = getAgentIpAddress();

            // 完全なサーバー登録メッセージを作成
            Map<String, Object> registrationData = new HashMap<>();
            registrationData.put("agentId", config.getAgentId());
            registrationData.put("agentName", config.getAgentId());
            registrationData.put("agentIp", agentIp);
            registrationData.put("hostname", hostname);
            registrationData.put("osName", osName);
            registrationData.put("osVersion", osVersion);
            registrationData.put("javaVersion", System.getProperty("java.version", "Unknown"));
            registrationData.put("nginxLogPaths", config.getNginxLogPaths());
            registrationData.put("iptablesEnabled", config.isEnableIptables());
            registrationData.put("agentVersion", AgentVersion.get());
            registrationData.put("timestamp", System.currentTimeMillis());

            String jsonData = objectMapper.writeValueAsString(registrationData);

            // データ長を計算（APIキー長 + APIキー + JSON長 + JSON）
            byte[] apiKeyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
            byte[] jsonBytes = jsonData.getBytes(StandardCharsets.UTF_8);
            int totalDataLength = 4 + apiKeyBytes.length + 4 + jsonBytes.length;

            // サーバー登録メッセージを送信（プロトコル統一）
            out.writeByte(MSG_TYPE_REGISTER);
            out.writeInt(totalDataLength); // 全データ長
            out.writeInt(apiKeyBytes.length); // APIキー長
            out.write(apiKeyBytes); // APIキー
            out.writeInt(jsonBytes.length); // JSON長
            out.write(jsonBytes); // JSON
            out.flush();

            byte responseCode = in.readByte();
            if (responseCode == RESPONSE_SUCCESS) {
                String registrationId = readString();
                updateLastActivity();
                AgentLogger.debug("サーバー登録に成功しました。登録ID: " + registrationId);
                return registrationId;
            } else {
                String errorMessage = readString();
                AgentLogger.error("サーバー登録時の認証に失敗しました: " + errorMessage);
                disconnect();
                return null;
            }

        } catch (Exception e) {
            AgentLogger.error("サーバー登録処理中にエラーが発生しました: " + e.getMessage());
            disconnect();
            return null;
        }
    }

    /**
     * サーバー登録解除処理
     */
    public synchronized boolean unregisterServer(String registrationId) {
        if (registrationId == null) {
            AgentLogger.error("サーバー登録解除に失敗しました: Not registered");
            return false;
        }

        try {
            if (!ensureConnection()) {
                AgentLogger.error("サーバー登録解除用の接続確立に失敗しました");
                return false;
            }

            out.writeByte(MSG_TYPE_UNREGISTER);
            writeString(registrationId);
            out.flush();

            byte responseCode = in.readByte();
            if (responseCode == RESPONSE_SUCCESS) {
                String responseMessage = readString();
                updateLastActivity();
                AgentLogger.debug("サーバー登録解除に成功しました: " + responseMessage);
                return true;
            } else {
                String errorMessage = readString();
                AgentLogger.error("サーバー登録解除に失敗しました: " + errorMessage);
                return false;
            }

        } catch (Exception e) {
            AgentLogger.error("サーバー登録解除処理中にエラーが発生しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * 文字列を書き込み
     */
    private void writeString(String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * 文字列を読み込み
     */
    private String readString() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 最終活動時刻を更新
     */
    private void updateLastActivity() {
        lastActivityTime = System.currentTimeMillis();
    }

    /**
     * 接続を切断
     */
    public synchronized void disconnect() {
        connected = false;
        authenticated = false;

        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            AgentLogger.debug("出力ストリーム切断エラー: " + e.getMessage());
        }

        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            AgentLogger.debug("入力ストリーム切断エラー: " + e.getMessage());
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            AgentLogger.debug("ソケット切断エラー: " + e.getMessage());
        }

        AgentLogger.info("TCP接続を切断しました");
    }

    /**
     * 接続エラーかどうかを判定
     */
    private boolean isConnectionError(Exception e) {
        String message = e.getMessage();
        return e instanceof IOException ||
               (message != null && (message.contains("Broken pipe") ||
                   message.contains("Connection reset") ||
                   message.contains("Socket closed")));
    }

    /**
     * セッション登録状態エラーかどうかを判定
     */
    private boolean isRegistrationStateError(byte responseCode, String errorMessage) {
        if (responseCode != RESPONSE_ERROR || errorMessage == null) {
            return false;
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        return normalized.contains("not registered") || normalized.contains("registration not found");
    }

    /**
     * 通常送信/再接続送信で共通使用するバッチサイズを決定
     */
    private int resolveSendBatchSize() {
        return Math.max(1, Math.min(config.getMaxBatchSize(), SHARED_SEND_BATCH_SIZE));
    }

    /**
     * 再接続直後の通信安定性を軽量確認
     */
    private boolean verifyConnectionStabilityAfterRegistration() {
        final int probeAttempts = 2;
        for (int i = 1; i <= probeAttempts; i++) {
            try {
                out.writeByte(MSG_TYPE_CONNECTION_TEST);
                out.writeInt(0);
                out.flush();

                byte responseCode = in.readByte();
                String responseMessage = readString();
                if (responseCode != RESPONSE_SUCCESS) {
                    AgentLogger.warn("再接続通信テスト失敗 (" + i + "/" + probeAttempts + "): " + responseMessage);
                    return false;
                }
                updateLastActivity();
            } catch (Exception e) {
                AgentLogger.warn("再接続通信テストエラー (" + i + "/" + probeAttempts + "): " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * リソースクリーンアップ
     */
    public synchronized void cleanup() {
        AgentLogger.info("LogTransmitterのクリーンアップを開始します");

        // 再接続タスクを停止
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel(false);
        }

        // 再接続エグゼキューターを停止
        if (!reconnectExecutor.isShutdown()) {
            reconnectExecutor.shutdown();
            try {
                if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // TCP接続を切断
        disconnect();

        // キューをクリア
        int logQueueSize = logQueue.size();
        logQueue.clear();

        if (logQueueSize > 0) {
            AgentLogger.warn("クリーンアップ時に " + logQueueSize + " 件のログがキューに残っていました");
        }

        AgentLogger.info("LogTransmitterのクリーンアップが完了しました");
    }

    /**
     * 手動で再接続モードを開始（起動時のサーバー未接続対応）
     */
    public synchronized void triggerReconnectMode() {
        if (!reconnecting) {
            AgentLogger.info("手動で再接続モードを開始します");
            startReconnectMode();
        } else {
            AgentLogger.debug("既に再接続モード中です");
        }
    }


    /**
     * ログバッチデータクラス
     */
    public static class LogBatch {
        private List<LogEntry> logs;
        private String agentId;

        // デフォルトコンストラクタ（Jackson用）
        public LogBatch() {
        }

        public LogBatch(List<LogEntry> logs, String agentId) {
            this.logs = logs;
            this.agentId = agentId;
        }

        /**
         * Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
         * @return ログリスト
         */
        public List<LogEntry> getLogs() {
            return logs;
        }

        /**
         * Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
         * @param logs ログリスト
         */
        public void setLogs(List<LogEntry> logs) {
            this.logs = logs;
        }

        /**
         * Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
         * @return エージェントID
         */
        public String getAgentId() {
            return agentId;
        }

        /**
         * Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
         * @param agentId エージェントID
         */
        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }
    }

    /**
     * ホスト名を取得
     */
    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            AgentLogger.warn("ホスト名の取得に失敗しました: " + e.getMessage());
            return "unknown-host";
        }
    }

    /**
     * OS名を取得（詳細版）
     */
    private String getOsName() {
        String osName = System.getProperty("os.name", "Unknown");
        String osArch = System.getProperty("os.arch", "");

        // Linux系の場合はディストリビューション情報を取得
        if (osName.toLowerCase().contains("linux")) {
            try {
                String distribution = getLinuxDistribution();
                if (!distribution.isEmpty()) {
                    return distribution + " (" + osArch + ")";
                }
            } catch (Exception e) {
                AgentLogger.debug("Linuxディストリビューション情報の取得に失敗: " + e.getMessage());
            }
        }

        // Windows系の場合はバージョン情報を付加
        if (osName.toLowerCase().contains("windows")) {
            return osName + " (" + osArch + ")";
        }

        return osName + " (" + osArch + ")";
    }

    /**
     * Linuxディストリビューション情報を取得
     */
    private String getLinuxDistribution() {
        try {
            // /etc/os-releaseファイルから情報を取得
            File osReleaseFile = new File("/etc/os-release");
            if (osReleaseFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(osReleaseFile))) {
                    String line;
                    String prettyName = null;
                    String name = null;
                    String version = null;

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PRETTY_NAME=")) {
                            prettyName = line.substring(12).replaceAll("\"", "");
                        } else if (line.startsWith("NAME=")) {
                            name = line.substring(5).replaceAll("\"", "");
                        } else if (line.startsWith("VERSION=")) {
                            version = line.substring(8).replaceAll("\"", "");
                        }
                    }

                    // PRETTY_NAMEが最も詳細な情報
                    if (prettyName != null && !prettyName.isEmpty()) {
                        return prettyName;
                    }

                    // PRETTY_NAMEがない場合はNAME + VERSIONを組み合わせ
                    if (name != null && version != null) {
                        return name + " " + version;
                    }

                    if (name != null) {
                        return name;
                    }
                }
            }

            // /etc/os-releaseがない場合は/etc/releaseファイルを試行
            File[] releaseFiles = {
                new File("/etc/redhat-release"),
                new File("/etc/debian_version"),
                new File("/etc/centos-release"),
                new File("/etc/fedora-release")
            };

            for (File file : releaseFiles) {
                if (file.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                        String line = reader.readLine();
                        if (line != null && !line.trim().isEmpty()) {
                            return line.trim();
                        }
                    }
                }
            }

        } catch (Exception e) {
            AgentLogger.debug("Linuxディストリビューション検出エラー: " + e.getMessage());
        }

        return "Linux";
    }

    /**
     * OSバージョンを取得（カーネルバージョンではなくOSバージョン）
     */
    private String getOsVersion() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        // Linux系の場合
        if (osName.contains("linux")) {
            try {
                // unameコマンドでカーネルバージョンを取得
                ProcessBuilder processBuilder = new ProcessBuilder("uname", "-r");
                Process process = processBuilder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String kernelVersion = reader.readLine();
                    if (kernelVersion != null && !kernelVersion.trim().isEmpty()) {
                        return "Kernel " + kernelVersion.trim();
                    }
                }
            } catch (Exception e) {
                AgentLogger.debug("Linuxカーネルバージョンの取得に失敗: " + e.getMessage());
            }

            // /etc/os-releaseからVERSION_IDを取得
            try {
                File osReleaseFile = new File("/etc/os-release");
                if (osReleaseFile.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(osReleaseFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("VERSION_ID=")) {
                                String versionId = line.substring(11).replaceAll("\"", "");
                                if (!versionId.isEmpty()) {
                                    return versionId;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AgentLogger.debug("Linux OSバージョンの取得に失敗: " + e.getMessage());
            }
        }

        // Windows系の場合
        if (osName.contains("windows")) {
            // Windows固有のバージョン情報取得を試行
            try {
                String version = System.getProperty("os.version", "");
                if (!version.isEmpty()) {
                    return version;
                }
            } catch (Exception e) {
                AgentLogger.debug("Windowsバージョンの取得に失敗: " + e.getMessage());
            }
        }

        // その他のOSまたは取得失敗時はシステムプロパティを使用
        return System.getProperty("os.version", "Unknown");
    }

    /**
     * エージェントのIPアドレスを取得（コンテナ環境対応版）
     */
    private String getAgentIpAddress() {
        // 方法1: 外部DNS接続による実IPアドレス取得（最も確実）
        String realIp = getRealIpByExternalConnection();
        if (!realIp.equals("127.0.0.1")) {
            AgentLogger.info("外部接続による実IPアドレスを取得: " + realIp);
            return realIp;
        }
        
        // 方法2: 設定ファイルから明示的に指定されたIPアドレス
        String configIp = getIpFromConfig();
        if (configIp != null && !configIp.isEmpty()) {
            AgentLogger.info("設定ファイルから指定されたIPアドレスを使用: " + configIp);
            return configIp;
        }
        
        // 方法3: ネットワークインターフェースから最適なIPを選択
        String interfaceIp = getBestNetworkInterfaceIp();
        if (!interfaceIp.equals("127.0.0.1")) {
            AgentLogger.info("ネットワークインターフェースから取得: " + interfaceIp);
            return interfaceIp;
        }
        
        AgentLogger.warn("実IPアドレスの取得に失敗、ループバックアドレスを使用");
        return "127.0.0.1";
    }
    
    /**
     * 外部DNS接続によって実際のIPアドレスを取得
     */
    private String getRealIpByExternalConnection() {
        try {
            // Google DNS(8.8.8.8)への接続を試行して実際のローカルIPアドレスを取得
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("8.8.8.8", 53), 3000);
                String localAddress = socket.getLocalAddress().getHostAddress();
                
                // ループバックアドレスでない場合は採用
                if (!localAddress.equals("127.0.0.1") && !localAddress.startsWith("127.")) {
                    return localAddress;
                }
            }
            
            // CloudFlare DNS(1.1.1.1)でも試行
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("1.1.1.1", 53), 3000);
                String localAddress = socket.getLocalAddress().getHostAddress();
                
                if (!localAddress.equals("127.0.0.1") && !localAddress.startsWith("127.")) {
                    return localAddress;
                }
            }
        } catch (Exception e) {
            AgentLogger.debug("外部接続によるIPアドレス取得に失敗: " + e.getMessage());
        }
        
        return "127.0.0.1";
    }
    
    /**
     * 設定ファイルから明示的に指定されたIPアドレスを取得
     */
    private String getIpFromConfig() {
        try {
            // AgentConfigに設定されたIPアドレスがあれば使用
            // 注意: 実際のconfig実装に合��せて調整が必要
            String configuredIp = config.getAgentIpAddress();
            if (configuredIp != null && !configuredIp.trim().isEmpty() && !configuredIp.equals("auto")) {
                return configuredIp.trim();
            }
        } catch (Exception e) {
            AgentLogger.debug("設定ファイルからのIPアドレス取得に失敗: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * ネットワークインターフェースから最適なIPアドレスを選択
     */
    private String getBestNetworkInterfaceIp() {
        try {
            List<String> candidateIps = new ArrayList<>();
            
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                // 無効・ループバック・仮想インターフェースをスキップ
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                
                // Dockerやコンテナ関連のインターフェースをスキップ
                String interfaceName = networkInterface.getName().toLowerCase();
                if (interfaceName.startsWith("docker") || 
                    interfaceName.startsWith("br-") || 
                    interfaceName.startsWith("veth") ||
                    interfaceName.startsWith("virbr")) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    
                    // IPv4アドレスのみ対象
                    if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                        String ip = inetAddress.getHostAddress();
                        candidateIps.add(ip);
                        AgentLogger.debug("IP候補を発見: " + ip + " (" + interfaceName + ")");
                    }
                }
            }
            
            // 優先順位でIPアドレスを選択
            return selectBestIpAddress(candidateIps);
            
        } catch (Exception e) {
            AgentLogger.debug("ネットワークインターフェースからのIPアドレス取得に失敗: " + e.getMessage());
        }
        
        return "127.0.0.1";
    }
    
    /**
     * IP候補から最適なものを選択
     */
    private String selectBestIpAddress(List<String> candidateIps) {
        if (candidateIps.isEmpty()) {
            return "127.0.0.1";
        }
        
        // 優先順位1: プライベートIPアドレス範囲（192.168.x.x, 10.x.x.x, 172.16-31.x.x）
        for (String ip : candidateIps) {
            if (isPrivateIpAddress(ip)) {
                return ip;
            }
        }
        
        // 優先順位2: その他のIPアドレス（パブリックIP等）
        for (String ip : candidateIps) {
            if (!ip.startsWith("169.254.")) { // リンクローカルアドレスを除外
                return ip;
            }
        }
        
        // フォールバック: 最初に見つかったIP
        return candidateIps.get(0);
    }
    
    /**
     * プライベートIPアドレス範囲かチェック
     */
    private boolean isPrivateIpAddress(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
