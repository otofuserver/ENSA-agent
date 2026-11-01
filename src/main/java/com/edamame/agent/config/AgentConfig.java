package com.edamame.agent.config;

import com.edamame.agent.util.AgentLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataOutputStream;
import java.io.DataInputStream;

/**
 * エージェント設定管理クラス
 * JSON設定ファイルの読み込み・管理を行う
 * Java 11-21対応
 * サーバー管理設定の形式:
 * "servers": [
 *   "# 例: メインNGINXサーバー",
 *   "main-nginx,/var/log/nginx/nginx.log",
 *   "",
 *   "# 例: APIサーバー", 
 *   "# api-server,/var/log/nginx/api.log"
 * ]
 *
 * @author Edamame Team
 * @version 1.2.0
 */
public class AgentConfig {

    // 設定ファイルパス
    private String configPath;
    private final ObjectMapper objectMapper;

    // エージェント情報
    private String agentName = "edamame-agent-default";
    private String agentDescription = "Edamame Security Agent";
    private String agentIpAddress = "auto";       // 追加: エージェントIPアドレス（"auto"で自動取得）

    // Edamameコンテナ接続情報（Agent専用通信）
    private String edamameHost = "localhost";
    private int edamamePort = 2591;                // Agent専用ポート
    private String protocol = "tcp";               // TCP通信
    private String apiKey = "CHANGE_ME";
    private int connectionTimeout = 30;
    private boolean socketKeepAlive = true;

    // サーバー情報
    private String serverName = "default-server";

    // ログ収集設定
    private List<String> nginxLogPaths = new ArrayList<>();
    private Map<String, List<String>> serverLogMappings = new HashMap<>(); // サーバー名 -> ログパスリストのマッピング
    private String defaultServerName = "main-nginx"; // デフォルトサーバー名
    private int logCollectionInterval = 10;       // 秒
    private String logFormat = "combined";
    private int maxBatchSize = 100;

    // iptables設定
    private boolean enableIptables = true;
    private int iptablesCheckInterval = 30;       // 秒
    private String iptablesChain = "INPUT";
    private int blockDuration = 3600;             // 秒

    // ハートビート設定
    private int heartbeatInterval = 60;           // 秒

    /**
     * コンストラクタ
     *
     * @param configPath 設定ファイルパス
     */
    public AgentConfig(String configPath) {
        this.configPath = configPath;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 設定ファイルを読み込み
     *
     * @return 読み込み成功の可否
     */
    public boolean load() {
        try {
            Path resolvedPath = Paths.get(configPath).toAbsolutePath().normalize();
            AgentLogger.info("設定ファイル読み込み開始: " + resolvedPath);

            if (!Files.exists(resolvedPath)) {
                AgentLogger.warn("設定ファイルが見つかりません: " + configPath + "、デフォルト設定を使用します");
                createDefaultConfig();
                return true;
            }

            String jsonContent = Files.readString(resolvedPath);
            JsonNode root = objectMapper.readTree(jsonContent);

            loadAgentConfig(root);
            loadEdamameConfig(root);
            loadLoggingConfig(root);
            loadIptablesConfig(root);
            loadHeartbeatConfig(root);  // 追加: heartbeat設定の読み込み
            loadAdvancedConfig(root);  // 追加: advanced設定の読み込み

            parseServerConfig();
            AgentLogger.info("設定ファイルの読み込みが完了しました: " + resolvedPath);
            return true;

        } catch (Exception e) {
            AgentLogger.error("設定ファイルの読み込みに失敗しました", e);
            return false;
        }
    }

    /**
     * 設定ファイルの書式を検証する
     *
     * @return 検証成功時true
     */
    public boolean validateConfigFormat() {
        try {
            Path resolvedPath = Paths.get(configPath).toAbsolutePath().normalize();
            if (!Files.exists(resolvedPath)) {
                AgentLogger.error("設定ファイルが見つかりません: " + resolvedPath);
                return false;
            }

            String jsonContent = Files.readString(resolvedPath);
            JsonNode root = objectMapper.readTree(jsonContent);

            if (!root.has("agent") || !root.get("agent").isObject()) {
                AgentLogger.error("設定書式エラー: agent セクションがありません");
                return false;
            }
            if (!root.has("edamame") || !root.get("edamame").isObject()) {
                AgentLogger.error("設定書式エラー: edamame セクションがありません");
                return false;
            }
            if (!root.has("logging") || !root.get("logging").isObject()) {
                AgentLogger.error("設定書式エラー: logging セクションがありません");
                return false;
            }

            JsonNode servers = root.path("logging").path("servers");
            if (!servers.isArray()) {
                AgentLogger.error("設定書式エラー: logging.servers は配列で指定してください");
                return false;
            }

            AgentLogger.info("設定ファイル書式チェックに成功しました: " + resolvedPath);
            return true;
        } catch (Exception e) {
            AgentLogger.error("設定ファイル書式チェックに失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * エージェント設定を読み込み
     */
    private void loadAgentConfig(JsonNode root) {
        JsonNode agentNode = root.path("agent");
        if (!agentNode.isMissingNode()) {
            agentName = agentNode.path("name").asText(agentName);
            agentDescription = agentNode.path("description").asText(agentDescription);
            agentIpAddress = agentNode.path("ipAddress").asText(agentIpAddress);
        }

        // 旧設定互換: agent.name が無い場合は server.name を利用
        if ("edamame-agent-default".equals(agentName)) {
            JsonNode serverNode = root.path("server");
            if (!serverNode.isMissingNode()) {
                String legacyServerName = serverNode.path("name").asText("").trim();
                if (!legacyServerName.isEmpty()) {
                    agentName = legacyServerName;
                    AgentLogger.warn("旧形式設定を検出しました。agent.name の代わりに server.name を使用します: " + agentName);
                }
            }
        }
    }

    /**
     * Edamame接続設定を読み込み
     */
    private void loadEdamameConfig(JsonNode root) {
        JsonNode edamameNode = root.path("edamame");
        if (!edamameNode.isMissingNode()) {
            edamameHost = edamameNode.path("host").asText(edamameHost);
            edamamePort = edamameNode.path("port").asInt(edamamePort);
            protocol = edamameNode.path("protocol").asText(protocol);
            apiKey = edamameNode.path("apiKey").asText(apiKey);
            connectionTimeout = edamameNode.path("connectionTimeout").asInt(connectionTimeout);
            socketKeepAlive = edamameNode.path("socketKeepAlive").asBoolean(socketKeepAlive);
        }
    }

    /**
     * ログ収集設定を読み込み
     */
    private void loadLoggingConfig(JsonNode root) {
        JsonNode loggingNode = root.path("logging");
        if (!loggingNode.isMissingNode()) {
            logCollectionInterval = loggingNode.path("collectionInterval").asInt(logCollectionInterval);
            logFormat = loggingNode.path("format").asText(logFormat);
            maxBatchSize = loggingNode.path("maxBatchSize").asInt(maxBatchSize);

            // debugModeのみで統一（logLevelは廃止）
            boolean debugMode = loggingNode.path("debugMode").asBoolean(false);
            AgentLogger.setDebugEnabled(debugMode);
            
            if (debugMode) {
                AgentLogger.debug("デバッグモードが有効になりました");
            }

            // サーバー設定の読み込み
            JsonNode serversNode = loggingNode.path("servers");
            if (serversNode.isArray()) {
                nginxLogPaths.clear();
                serverLogMappings.clear(); // 追加: サーバー名とログパスのマッピングをクリア
                AgentLogger.debug("サーバー設定の読み込みを開始します。配列要素数: " + serversNode.size());

                for (JsonNode serverLine : serversNode) {
                    String line = serverLine.asText().trim();
                    AgentLogger.debug("処理中の設定行: '" + line + "'");

                    if (!line.isEmpty() && !line.startsWith("#")) {
                        String[] parts = line.split(",", 2);
                        AgentLogger.debug("分割結果: parts.length=" + parts.length + ", parts=" + java.util.Arrays.toString(parts));

                        if (parts.length == 2) {
                            String serverName = parts[0].trim();
                            String logPath = parts[1].trim();

                            nginxLogPaths.add(logPath);
                            serverLogMappings.computeIfAbsent(serverName, k -> new ArrayList<>()).add(logPath);

                            AgentLogger.info("サーバー設定を登録: " + serverName + " -> " + logPath);
                        } else {
                            AgentLogger.warn("無効なサーバー設定行をスキップ: " + line);
                        }
                    } else {
                        AgentLogger.debug("コメント行または空行をスキップ: " + line);
                    }
                }

                AgentLogger.info("サーバー設定の読み込み完了。登録されたマッピング数: " + serverLogMappings.size());
                AgentLogger.debug("最終的なマッピング: " + serverLogMappings);
            } else {
                AgentLogger.warn("servers設定が配列ではありません: " + serversNode.getNodeType());
            }
        }
    }

    /**
     * iptables設定を読み込み
     */
    private void loadIptablesConfig(JsonNode root) {
        JsonNode iptablesNode = root.path("iptables");
        if (iptablesNode.isMissingNode()) {
            // Windows向け設定名の互換対応
            iptablesNode = root.path("defender");
        }
        if (!iptablesNode.isMissingNode()) {
            enableIptables = iptablesNode.path("enabled").asBoolean(enableIptables);
            iptablesCheckInterval = iptablesNode.path("checkInterval").asInt(iptablesCheckInterval);
            iptablesChain = iptablesNode.path("chain").asText(iptablesChain);
            blockDuration = iptablesNode.path("blockDuration").asInt(blockDuration);
        }
    }

    /**
     * ハートビート設定を読み込み
     */
    private void loadHeartbeatConfig(JsonNode root) {
        JsonNode heartbeatNode = root.path("heartbeat");
        if (!heartbeatNode.isMissingNode()) {
            heartbeatInterval = heartbeatNode.path("interval").asInt(heartbeatInterval);
        }
    }

    /**
     * 高度な設定を読み込み（v1.12.0で廃止、loggingセクションに統合）
     */
    private void loadAdvancedConfig(JsonNode root) {
        // 後方互換性のため、advancedセクションが存在する場合は読み込む
        JsonNode advancedNode = root.path("advanced");
        if (!advancedNode.isMissingNode()) {
            AgentLogger.warn("advancedセクションは廃止されました。loggingセクションのdebugModeを使用してください");

            // 旧設定からの移行サポート
            boolean debugMode = advancedNode.path("debugMode").asBoolean(false);
            AgentLogger.setDebugEnabled(debugMode);
            
            if (debugMode) {
                AgentLogger.debug("旧advancedセクションからデバッグモードが有効になりました");
            }
        }
    }

    /**
     * TCP接続テスト（Connection Test用特別プロトコル）
     * サーバー側で接続テストであることを認識させ、認証エラーを回避
     */
    public boolean testTcpConnection() {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(3000); // 3秒タイムアウト
            socket.connect(new InetSocketAddress(edamameHost, edamamePort), 3000);
            
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            
            // 接続テスト専用プロトコル（MSG_TYPE_CONNECTION_TEST: 0x09）
            out.writeByte(0x09); // 新しいメッセージタイプ
            out.writeInt(0);     // データ長���0
            out.flush();
            
            // レスポンスを受信
            byte responseCode = in.readByte();
            boolean testSuccess = (responseCode == 0x00); // RESPONSE_SUCCESS

            AgentLogger.info("TCP接続テストが成功しました: " + edamameHost + ":" + edamamePort);
            return testSuccess;

        } catch (Exception e) {
            AgentLogger.error("TCP接続テストに失敗しました: " + edamameHost + ":" + edamamePort, e);
            return false;
        }
    }

    /**
     * サーバー設定の解析
     */
    public void parseServerConfig() {
        int validServers = 0;
        for (String logPath : nginxLogPaths) {
            if (containsWildcard(logPath)) {
                validServers++;
                AgentLogger.info("ワイルドカード付きログ設定を登録: " + logPath);
            } else if (Files.exists(Paths.get(logPath))) {
                validServers++;
            } else {
                AgentLogger.warn("ログファイルが存在しません: " + logPath);
            }
        }
        AgentLogger.info(validServers + " 個のサーバーログ設定を解析しました");
    }

    /**
     * ワイルドカードを含むログパスかを判定
     */
    private boolean containsWildcard(String path) {
        return path.contains("*") || path.contains("?");
    }

    /**
     * デフォルト設定ファイルを作成
     * <p>
     * Linux環境の場合、以下の順序でディレクトリ・ファイルを確認・作成する。
     * 各ステップで作成に失敗した場合は★（実行カレントディレクトリへの生成）にフォールバックする。
     * <ol>
     *   <li>/etc/Edamame ディレクトリが無ければ作成（失敗時は★へ）</li>
     *   <li>/etc/Edamame/agent ディレクトリが無ければ作成（失敗時は★へ）</li>
     *   <li>/etc/Edamame/agent/agent-config.json が無ければ作成（失敗時は★へ）</li>
     * </ol>
     * ★: 実行カレントディレクトリに agent-config.json を生成する。
     * Windows環境の場合は従来通り configPath に生成する。
     * </p>
     */
    private void createDefaultConfig() {
        String defaultConfig = isWindowsDefaultProfile()
            ? buildWindowsDefaultConfig()
            : buildStandardDefaultConfig();

        // Windows環境は ProgramData 配下の configPath に生成
        if (isWindowsDefaultProfile()) {
            createDefaultConfigForWindows(defaultConfig);
            return;
        }

        // Linux環境: /etc/Edamame → /etc/Edamame/agent → agent-config.json の順に確認・作成
        Path etcEdamame = Paths.get("/etc/Edamame");
        if (!Files.isDirectory(etcEdamame)) {
            AgentLogger.info("/etc/Edamame ディレクトリが存在しないため作成を試みます");
            try {
                Files.createDirectories(etcEdamame);
                AgentLogger.info("/etc/Edamame ディレクトリを作成しました");
            } catch (IOException e) {
                AgentLogger.warn("/etc/Edamame ディレクトリの作成に失敗しました: " + e.getMessage() + " → カレントディレクトリに設定ファイルを生成します");
                createDefaultConfigInCurrentDir(defaultConfig);
                return;
            }
        }

        Path etcEdamameAgent = etcEdamame.resolve("agent");
        if (!Files.isDirectory(etcEdamameAgent)) {
            AgentLogger.info("/etc/Edamame/agent ディレクトリが存在しないため作成を試みます");
            try {
                Files.createDirectories(etcEdamameAgent);
                AgentLogger.info("/etc/Edamame/agent ディレクトリを作成しました");
            } catch (IOException e) {
                AgentLogger.warn("/etc/Edamame/agent ディレクトリの作成に失敗しました: " + e.getMessage() + " → カレントディレクトリに設定ファイルを生成します");
                createDefaultConfigInCurrentDir(defaultConfig);
                return;
            }
        }

        Path etcConfigFile = etcEdamameAgent.resolve("agent-config.json");
        if (!Files.exists(etcConfigFile)) {
            AgentLogger.info("/etc/Edamame/agent/agent-config.json が存在しないため作成を試みます");
            try {
                Files.writeString(etcConfigFile, defaultConfig);
                AgentLogger.info("/etc/Edamame/agent/agent-config.json を作成しました");
                this.configPath = etcConfigFile.toString();
            } catch (IOException e) {
                AgentLogger.warn("/etc/Edamame/agent/agent-config.json の作成に失敗しました: " + e.getMessage() + " → カレントディレクトリに設定ファイルを生成します");
                createDefaultConfigInCurrentDir(defaultConfig);
            }
            return;
        }

        // /etc/Edamame/agent/agent-config.json が既に存在する場合はそちらを使用
        AgentLogger.info("/etc/Edamame/agent/agent-config.json を使用します");
        this.configPath = etcConfigFile.toString();
    }

    /**
     * 実行カレントディレクトリに agent-config.json を生成する
     *
     * @param defaultConfig 生成するデフォルト設定のJSON文字列
     */
    private void createDefaultConfigInCurrentDir(String defaultConfig) {
        Path currentDirConfig = Paths.get(System.getProperty("user.dir"), "agent-config.json");
        try {
            Files.writeString(currentDirConfig, defaultConfig);
            AgentLogger.info("カレントディレクトリにデフォルト設定ファイルを作成しました: " + currentDirConfig);
            // 次回読み込み先をカレントディレクトリに更新
            this.configPath = currentDirConfig.toString();
        } catch (IOException e) {
            AgentLogger.error("デフォルト設定ファイルの作成に失敗しました: " + e.getMessage());
        }
    }

    /**
     * Windows環境でデフォルト設定ファイルを生成する
     *
     * ProgramData配下のディレクトリが無い場合は作成し、
     * 作成失敗時のみカレントディレクトリにフォールバックする。
     *
     * @param defaultConfig 生成するデフォルト設定のJSON文字列
     */
    private void createDefaultConfigForWindows(String defaultConfig) {
        Path windowsConfig = Paths.get(configPath).toAbsolutePath().normalize();
        Path parent = windowsConfig.getParent();

        if (parent != null && !Files.isDirectory(parent)) {
            try {
                Files.createDirectories(parent);
                AgentLogger.info("Windows設定ディレクトリを作成しました: " + parent);
            } catch (IOException e) {
                AgentLogger.warn("Windows設定ディレクトリの作成に失敗しました: " + e.getMessage() + " → カレントディレクトリに設定ファイルを生成します");
                createDefaultConfigInCurrentDir(defaultConfig);
                return;
            }
        }

        try {
            Files.writeString(windowsConfig, defaultConfig);
            AgentLogger.info("Windowsデフォルト設定ファイルを作成しました: " + windowsConfig);
            this.configPath = windowsConfig.toString();
        } catch (IOException e) {
            AgentLogger.warn("Windowsデフォルト設定ファイルの作成に失敗しました: " + e.getMessage() + " → カレントディレクトリに設定ファイルを生成します");
            createDefaultConfigInCurrentDir(defaultConfig);
        }
    }

    /**
     * Windows向けプロファイルを生成すべきか判定
     */
    private boolean isWindowsDefaultProfile() {
        String fileName = Paths.get(configPath).getFileName() == null
            ? ""
            : Paths.get(configPath).getFileName().toString().toLowerCase();
        if (fileName.contains("windows")) {
            return true;
        }
        return System.getProperty("os.name", "unknown").toLowerCase().contains("win");
    }

    /**
     * 標準(Linux寄り)のデフォルト設定テンプレート
     */
    private String buildStandardDefaultConfig() {
        return """
        {
          "agent": {
            "name": "edamame-agent-01",
            "description": "Edamame Security Agent Instance 01"
          },
          "edamame": {
            "host": "localhost",
            "port": 2591,
            "protocol": "tcp",
            "apiKey": "CHANGE_ME",
            "description": "Edamame Security Analyzer Container - TCP with API-key payload encryption",
            "connectionTimeout": 30,
            "socketKeepAlive": true
          },
          "logging": {
            "servers": [
              "main-nginx,/var/log/nginx/access.log",
              "main-nginx,/var/log/nginx/error.log"
            ],
            "collectionInterval": 10,
            "format": "combined",
            "maxBatchSize": 100,
            "debugMode": true,
            "description": "Log collection settings with server mapping"
          },
          "iptables": {
            "enabled": true,
            "checkInterval": 30,
            "chain": "INPUT",
            "blockDuration": 3600,
            "description": "Firewall management settings"
          },
          "heartbeat": {
            "interval": 60,
            "timeout": 30,
            "description": "Agent health monitoring"
          }
        }
        """;
    }

    /**
     * Windows向けのデフォルト設定テンプレート
     */
    private String buildWindowsDefaultConfig() {
        return """
        {
          "agent": {
            "name": "edamame-agent-windows-01",
            "description": "Edamame Security Agent Instance for Windows"
          },
          "edamame": {
            "host": "localhost",
            "port": 2591,
            "protocol": "tcp",
            "apiKey": "CHANGE_ME",
            "description": "Edamame Security Analyzer Container - TCP with API-key payload encryption",
            "connectionTimeout": 30,
            "socketKeepAlive": true
          },
          "logging": {
            "servers": [
              "windows-iis,C:\\\\inetpub\\\\logs\\\\LogFiles\\\\W3SVC1\\\\*.log",
              "nginx-windows,C:\\\\ProgramData\\\\Docker\\\\containers\\\\*\\\\nginx-access.log"
            ],
            "collectionInterval": 10,
            "format": "combined",
            "maxBatchSize": 100,
            "debugMode": false,
            "description": "Log collection settings with server mapping"
          },
          "defender": {
            "enabled": true,
            "checkInterval": 30,
            "chain": "WINDOWS_FIREWALL_INBOUND",
            "blockDuration": 3600,
            "description": "Windows Firewall inbound rule management settings"
          },
          "heartbeat": {
            "interval": 60,
            "timeout": 30,
            "description": "Agent health monitoring"
          }
        }
        """;
    }

    // Getter メソッド
    public String getAgentId() { return agentName; }
    public String getAgentIpAddress() { return agentIpAddress; }  // 追加: エージェントIPアドレス取得
    
    public String getEdamameHost() { return edamameHost; }
    public int getEdamamePort() { return edamamePort; }
    public String getApiKey() { return apiKey; }
    
    public String getServerName() { return serverName; }
    
    public List<String> getNginxLogPaths() { return nginxLogPaths; }
    public int getLogCollectionInterval() { return logCollectionInterval; }
    public String getLogFormat() { return logFormat; }
    public int getMaxBatchSize() { return maxBatchSize; }
    
    public boolean isEnableIptables() { return enableIptables; }
    public int getIptablesCheckInterval() { return iptablesCheckInterval; }
    public String getIptablesChain() { return iptablesChain; }
    
    public int getHeartbeatInterval() { return heartbeatInterval; }
    
    /**
     * サーバー名とログパスのマッピングを取得
     * @return サーバー名 -> ログパスのマップ
     */
    public Map<String, List<String>> getServerLogMappings() { return serverLogMappings; }
    
    /**
     * ログパスからサーバー名を取得
     * @param logPath ログファイルパス
     * @return サーバー名（見つからない場合はデフォルトサーバー名）
     */
    public String getServerNameByLogPath(String logPath) {
        AgentLogger.debug("サーバー名を検索中: logPath='" + logPath + "'");
        AgentLogger.debug("利用可能なマッピング: " + serverLogMappings);
        
        for (Map.Entry<String, List<String>> entry : serverLogMappings.entrySet()) {
            if (entry.getValue().contains(logPath)) {
                AgentLogger.debug("マッチしました: " + entry.getKey() + " -> " + logPath);
                return entry.getKey();
            }
        }
        
        // 設定から見つからない場合はデフォルトサーバー名を返す
        AgentLogger.info("ログパス " + logPath + " にはデフォルトサーバー名を使用: " + defaultServerName);
        return defaultServerName;
    }

}