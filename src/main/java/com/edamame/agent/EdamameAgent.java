package com.edamame.agent;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.log.LogCollector;
import com.edamame.agent.network.LogTransmitter;
import com.edamame.agent.system.IptablesManager;
import com.edamame.agent.util.AgentLogger;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Edamame Security Analyzer Agent
 *
 * ホスト上またはDockerコンテナ上のNginxログを収集し、
 * 枝豆コンテナへ転送、iptables操作、サーバー登録管理を行う
 * Java 11-21対応
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class EdamameAgent {

    private static final String AGENT_VERSION = "1.0.0";
    private static final String LINUX_SYSTEM_CONFIG_PATH = "/etc/Edamame/agent/agent-config.json";

    private final AgentConfig config;
    private final ScheduledExecutorService executor;

    private LogCollector logCollector;
    private LogTransmitter logTransmitter;
    private IptablesManager iptablesManager = null;

    private volatile boolean running = false;
    private String registrationId = null;

    private record StartupOptions(String configPath, boolean showHelp, boolean showVersion, String errorMessage) {}

    /**
     * エージェントインスタンスを初期化
     *
     * @param configPath 設定ファイルパス
     */
    public EdamameAgent(String configPath) {
        this.config = new AgentConfig(configPath);
        // LogCollectorとLogTransmitterの初期化は設定読み込み後に移動
        this.logCollector = null; // 初期化を遅延
        this.logTransmitter = null; // 初期化を遅延
        this.executor = Executors.newScheduledThreadPool(4);

        AgentLogger.debug("Edamame Agent v" + AGENT_VERSION + " を初期化しました");
    }

    /**
     * エージェントを起動
     */
    public void start() {
        if (running) {
            AgentLogger.warn("エージェントは既に実行中です");
            return;
        }

        try {
            // 設定ファイル読み込み
            if (!config.load()) {
                AgentLogger.error("設定ファイルの読み込みに失敗しました");
                return;
            }

            // サーバー接続状態に関係なくエージェントを起動
            AgentLogger.debug("Edamameエージェントを開始します（サーバー接続は自動管理）");

            // LogCollectorとLogTransmitterを初期化（設定読み込み後）
            logCollector = new LogCollector(config);
            logTransmitter = new LogTransmitter(config);

            // IptablesManagerを初期化（LogTransmitter初期化後に移動）
            iptablesManager = new IptablesManager(config, logTransmitter);

            // 再接続時の登録ID更新コールバックを設定
            logTransmitter.setReconnectionSuccessCallback(() -> {
                // 再接続時に新しい登録IDを設定する処理は、LogTransmitter内で既に新しいIDで登録済み
                AgentLogger.info("再接続が完了しました。新しいセッションで動作を継続します");
            });

            // エージェント起動完了をマーク
            running = true;
            AgentLogger.info("Edamameエージェントが正常に開始されました");

            // 初期サーバー接続を試行（失敗時は再接続モードに移行）
            executor.schedule(this::initialServerConnection, 2, TimeUnit.SECONDS);

            // ログ収集開始（10秒後に開始して初期接続処理と分離）
            executor.scheduleWithFixedDelay(
                this::collectAndTransmitLogs,
                10,
                config.getLogCollectionInterval(),
                TimeUnit.SECONDS
            );

            // iptables管理開始（20秒後に開始してログ収集と確実に分離）
            executor.scheduleWithFixedDelay(
                this::manageIptables,
                20,
                config.getIptablesCheckInterval(),
                TimeUnit.SECONDS
            );

            // ハートビート送信（30秒後に開始して他タスクと完全に分離）
            executor.scheduleWithFixedDelay(
                this::sendHeartbeat,
                30,
                config.getHeartbeatInterval(),
                TimeUnit.SECONDS
            );

        } catch (Exception e) {
            AgentLogger.error("エージェント起動中にエラーが発生しました: " + e.getMessage());
            stop();
        }
    }

    /**
     * 初期サーバー接続処理
     */
    private void initialServerConnection() {
        try {
            AgentLogger.info("サーバーへの初期接続を試行します...");

            // 接続テスト（軽量）
            if (config.testTcpConnection()) {
                AgentLogger.info("サーバー接続テストに成功しました");

                // サーバー登録を試行
                registrationId = logTransmitter.registerServer();
                if (registrationId != null) {
                    // LogTransmitter側でログ出力されるため、ここでの重複ログは削除
                    return; // 成功時は通常動作
                } else {
                    AgentLogger.warn("サーバー登録に失敗しました。再接続待機モードに移行します");
                }
            } else {
                AgentLogger.warn("サーバー接続テストに失敗しました。再接続待機モードに移行します");
            }

            // 接続失敗時は再接続待機モードに移行
            logTransmitter.triggerReconnectMode();

        } catch (Exception e) {
            AgentLogger.warn("初期サーバー接続中にエラーが発生しました: " + e.getMessage() + "。再接続待機モードに移行します");
            logTransmitter.triggerReconnectMode();
        }
    }

    /**
     * ログ収集・転送処理
     */
    private void collectAndTransmitLogs() {
        try {
            var logs = logCollector.collectNewLogs();
            if (!logs.isEmpty()) {
                boolean success = logTransmitter.transmitLogs(logs);
                if (success) {
                    AgentLogger.info(logs.size() + " 件のログエントリを転送しました");
                } else {
                    AgentLogger.warn("ログ転送に失敗しました");
                }
            }
        } catch (Exception e) {
            AgentLogger.error("ログ収集・転送中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * iptables管理処理
     */
    private void manageIptables() {
        try {
            iptablesManager.processBlockRequests();
        } catch (Exception e) {
            AgentLogger.error("iptables管理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * ハートビート送信処理
     */
    private void sendHeartbeat() {
        try {
            logTransmitter.sendHeartbeat();
        } catch (Exception e) {
            AgentLogger.error("ハートビート送信中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * エージェントを停止
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        try {
            // スケジュールされたタスクを停止
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // サーバー登録を解除
            if (registrationId != null) {
                logTransmitter.unregisterServer(registrationId);
            }

            // LogTransmitterのクリーンアップ（再接続タスクとキューを含む）
            logTransmitter.cleanup();

            AgentLogger.info("Edamameエージェントを停止しました");

        } catch (Exception e) {
            AgentLogger.error("エージェント停止中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * シャットダウンフックを設定
     */
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("シャットダウンシグナルを受信しました");
            stop();
        }));
    }

    /**
     * 起動オプションを解析する
     *
     * 対応オプション:
     * -c, --config <path>, --config=<path>
     * -h, --help
     * -v, --version
     * 既存互換として、オプション無しの先頭引数も設定ファイルパスとして扱う。
     *
     * @param args コマンドライン引数
     * @return 解析結果
     */
    private static StartupOptions parseStartupOptions(String[] args) {
        String configPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("-h".equals(arg) || "--help".equals(arg)) {
                return new StartupOptions(null, true, false, null);
            }

            if ("-v".equals(arg) || "--version".equals(arg)) {
                return new StartupOptions(null, false, true, null);
            }

            if ("-c".equals(arg) || "--config".equals(arg)) {
                if (i + 1 >= args.length) {
                    return new StartupOptions(null, false, false, "起動オプションエラー: " + arg + " に設定ファイルパスが指定されていません");
                }
                String optionValue = args[++i].trim();
                if (optionValue.isEmpty()) {
                    return new StartupOptions(null, false, false, "起動オプションエラー: " + arg + " の設定ファイルパスが空です");
                }
                configPath = optionValue;
                continue;
            }

            if (arg.startsWith("--config=")) {
                String optionValue = arg.substring("--config=".length()).trim();
                if (optionValue.isEmpty()) {
                    return new StartupOptions(null, false, false, "起動オプションエラー: --config= の設定ファイルパスが空です");
                }
                configPath = optionValue;
                continue;
            }

            // 後方互換: 先頭の非オプション引数を設定ファイルパスとして扱う
            if (!arg.startsWith("-") && configPath == null) {
                configPath = arg;
                continue;
            }

            return new StartupOptions(null, false, false, "起動オプションエラー: 未対応の引数です: " + arg);
        }

        return new StartupOptions(configPath, false, false, null);
    }

    /**
     * 起動オプションのヘルプを表示する
     *
     * @param defaultConfigPath 既定設定ファイルパス
     */
    private static void printUsage(String defaultConfigPath) {
        System.out.println("使用方法: java -jar EdamameAgent-<version>.jar [オプション] [設定ファイルパス]");
        System.out.println("  -c, --config <path>   設定ファイルパスを指定");
        System.out.println("      --config=<path>   設定ファイルパスを指定");
        System.out.println("  -v, --version         バージョンのみ表示");
        System.out.println("  -h, --help            このヘルプを表示");
        System.out.println("  [設定ファイルパス]     既存互換の位置引数（先頭1つ）");
        System.out.println("既定設定ファイル: " + defaultConfigPath);
    }

    /**
     * メインエントリーポイント
     */
    public static void main(String[] args) {
        String osName = System.getProperty("os.name", "unknown");
        boolean isWindows = osName.toLowerCase().contains("win");
        String defaultConfigPath = isWindows ? "agent-config-windows.json" : "agent-config.json";
        if (!isWindows && Files.exists(Paths.get(LINUX_SYSTEM_CONFIG_PATH))) {
            defaultConfigPath = LINUX_SYSTEM_CONFIG_PATH;
        }

        StartupOptions startupOptions = parseStartupOptions(args);
        if (startupOptions.showVersion()) {
            System.out.println(AGENT_VERSION);
            return;
        }
        if (startupOptions.showHelp()) {
            printUsage(defaultConfigPath);
            return;
        }
        if (startupOptions.errorMessage() != null) {
            System.err.println(startupOptions.errorMessage());
            printUsage(defaultConfigPath);
            System.exit(1);
            return;
        }
        String configPath = startupOptions.configPath();
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = defaultConfigPath;
        }
        String resolvedConfigPath = Paths.get(configPath).toAbsolutePath().normalize().toString();

        AgentLogger.info("Edamame Agent v" + AGENT_VERSION + " を開始しています...");
        AgentLogger.info("OS自動判定結果: " + osName + " -> defaultConfig=" + defaultConfigPath);
        AgentLogger.info("設定ファイルを使用: " + configPath);
        AgentLogger.info("設定ファイル絶対パス: " + resolvedConfigPath);

        EdamameAgent agent = new EdamameAgent(configPath);
        agent.setupShutdownHook();
        agent.start();

        // メインスレッドを維持
        try {
            while (agent.running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            AgentLogger.info("メインスレッドが中断されました");
            agent.stop();
        }
    }
}
