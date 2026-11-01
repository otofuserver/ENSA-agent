package com.edamame.agent;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.log.LogCollector;
import com.edamame.agent.network.LogTransmitter;
import com.edamame.agent.system.IptablesManager;
import com.edamame.agent.util.AgentLogger;
import com.edamame.agent.util.AgentVersion;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Edamame Security Analyzer Agent
 *
 * ホスト上またはDockerコンテナ上のNginxログを収集し、
 * 枝豆コンテナへ転送、iptables操作、サーバー登録管理を行う
 * Java 11-21対応
 *
 * @author Edamame Team
 * @version build.gradle の version を参照
 */
public class EdamameAgent {

    private static final String AGENT_VERSION = AgentVersion.get();
    private static final String LINUX_SYSTEM_CONFIG_PATH = "/etc/Edamame/agent/agent-config.json";
    private static final String WINDOWS_PROGRAM_DATA_DIR = "ProgramData";
    private static final String WINDOWS_FALLBACK_PROGRAM_DATA_PATH = "C:\\ProgramData";
    private static final String WINDOWS_SYSTEM_CONFIG_PATH = "Edamame\\agent\\agent-config.json";
    private static final String WINDOWS_RELOAD_COMMAND_PATH = "Edamame\\agent\\reload.signal";
    private static final String WINDOWS_COMMAND_DIR = "Edamame\\agent\\bin";
    private static final String WINDOWS_COMMAND_FILE_NAME = "ensaa.cmd";
    private static final String WINDOWS_SERVICE_NAME = "EdamameAgent";
    private static final String WINDOWS_SERVICE_DISPLAY_NAME = "Edamame Agent";
    private static final String LINUX_RELOAD_COMMAND_PATH = "/tmp/edamame-agent.reload";

    private final AgentConfig config;
    private final ScheduledExecutorService executor;

    private LogCollector logCollector;
    private LogTransmitter logTransmitter;
    private IptablesManager iptablesManager = null;
    private ScheduledFuture<?> logCollectionTask;
    private ScheduledFuture<?> iptablesTask;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reloadCommandTask;

    private volatile boolean running = false;
    private String registrationId = null;
    private long lastReloadCommandTimestamp = -1L;

    private enum StartupCommand {
        START,
        TEST,
        RELOAD,
        SERVICE_INSTALL,
        SERVICE_UNINSTALL,
        SERVICE_START,
        SERVICE_STOP,
        SERVICE_STATUS
    }

    private record StartupOptions(String configPath, StartupCommand command, boolean showHelp, boolean showVersion, String errorMessage) {}

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
            initializeReloadCommandTimestamp();
            schedulePeriodicTasks();

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
            LogCollector collector = logCollector;
            LogTransmitter transmitter = logTransmitter;
            if (collector == null || transmitter == null) {
                return;
            }
            var logs = collector.collectNewLogs();
            if (!logs.isEmpty()) {
                boolean success = transmitter.transmitLogs(logs);
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
            IptablesManager manager = iptablesManager;
            if (manager == null) {
                return;
            }
            manager.processBlockRequests();
        } catch (Exception e) {
            AgentLogger.error("iptables管理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * ハートビート送信処理
     */
    private void sendHeartbeat() {
        try {
            LogTransmitter transmitter = logTransmitter;
            if (transmitter == null) {
                return;
            }
            transmitter.sendHeartbeat();
        } catch (Exception e) {
            AgentLogger.error("ハートビート送信中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * 設定を再読み込みし、関連コンポーネントを再初期化する
     *
     * @return リロード成功の可否
     */
    public synchronized boolean reloadConfiguration() {
        if (!running) {
            AgentLogger.warn("エージェント停止中のため設定リロードをスキップしました");
            return false;
        }

        AgentLogger.info("設定リロードを開始します");

        if (!config.load()) {
            AgentLogger.error("設定リロードに失敗しました: 設定ファイルの読み込みに失敗");
            return false;
        }

        LogTransmitter previousTransmitter = logTransmitter;
        String previousRegistrationId = registrationId;

        cancelPeriodicTasks();

        try {
            logCollector = new LogCollector(config);
            logTransmitter = new LogTransmitter(config);
            iptablesManager = new IptablesManager(config, logTransmitter);
            registrationId = null;

            logTransmitter.setReconnectionSuccessCallback(() ->
                AgentLogger.info("再接続が完了しました。新しいセッションで動作を継続します")
            );

            if (previousTransmitter != null && previousRegistrationId != null) {
                previousTransmitter.unregisterServer(previousRegistrationId);
            }

            if (previousTransmitter != null) {
                previousTransmitter.cleanup();
            }

            executor.schedule(this::initialServerConnection, 1, TimeUnit.SECONDS);
            schedulePeriodicTasks();

            AgentLogger.info("設定リロードが完了しました");
            return true;
        } catch (Exception e) {
            AgentLogger.error("設定リロード中にエラーが発生しました: " + e.getMessage(), e);
            if (previousTransmitter != null) {
                logTransmitter = previousTransmitter;
            }
            return false;
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
            cancelPeriodicTasks();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // サーバー登録を解除
            if (registrationId != null && logTransmitter != null) {
                logTransmitter.unregisterServer(registrationId);
            }

            // LogTransmitterのクリーンアップ（再接続タスクとキューを含む）
            if (logTransmitter != null) {
                logTransmitter.cleanup();
            }

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
     * 定期タスクをスケジュールする
     */
    private void schedulePeriodicTasks() {
        // ログ収集開始（10秒後に開始して初期接続処理と分離）
        logCollectionTask = executor.scheduleWithFixedDelay(
            this::collectAndTransmitLogs,
            10,
            config.getLogCollectionInterval(),
            TimeUnit.SECONDS
        );

        // iptables管理開始（20秒後に開始してログ収集と確実に分離）
        iptablesTask = executor.scheduleWithFixedDelay(
            this::manageIptables,
            20,
            config.getIptablesCheckInterval(),
            TimeUnit.SECONDS
        );

        // ハートビート送信（30秒後に開始して他タスクと完全に分離）
        heartbeatTask = executor.scheduleWithFixedDelay(
            this::sendHeartbeat,
            30,
            config.getHeartbeatInterval(),
            TimeUnit.SECONDS
        );

        reloadCommandTask = executor.scheduleWithFixedDelay(
            this::checkReloadCommand,
            5,
            5,
            TimeUnit.SECONDS
        );
    }

    /**
     * 定期タスクを停止する
     */
    private void cancelPeriodicTasks() {
        if (logCollectionTask != null) {
            logCollectionTask.cancel(false);
            logCollectionTask = null;
        }
        if (iptablesTask != null) {
            iptablesTask.cancel(false);
            iptablesTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (reloadCommandTask != null) {
            reloadCommandTask.cancel(false);
            reloadCommandTask = null;
        }
    }

    /**
     * 設定リロードコマンドファイルの最終更新時刻を初期化する
     */
    private void initializeReloadCommandTimestamp() {
        try {
            Path reloadCommandPath = resolveReloadCommandPath();
            if (Files.exists(reloadCommandPath)) {
                lastReloadCommandTimestamp = Files.getLastModifiedTime(reloadCommandPath).toMillis();
            }
        } catch (Exception e) {
            AgentLogger.warn("設定リロードコマンド初期化に失敗しました: " + e.getMessage());
        }
    }

    /**
     * 設定リロードコマンドを監視する
     */
    private void checkReloadCommand() {
        try {
            Path reloadCommandPath = resolveReloadCommandPath();
            if (!Files.exists(reloadCommandPath)) {
                return;
            }
            long modifiedAt = Files.getLastModifiedTime(reloadCommandPath).toMillis();
            if (modifiedAt <= lastReloadCommandTimestamp) {
                return;
            }
            lastReloadCommandTimestamp = modifiedAt;
            AgentLogger.info("設定リロードコマンドを検知しました");
            reloadConfiguration();
        } catch (Exception e) {
            AgentLogger.warn("設定リロードコマンド監視でエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * Windows実行環境か判定する
     */
    private boolean isWindowsRuntime() {
        return System.getProperty("os.name", "unknown").toLowerCase().contains("win");
    }

    /**
     * 実行環境に応じたリロードコマンドファイルパスを返す
     */
    private Path resolveReloadCommandPath() {
        return resolveReloadCommandPath(isWindowsRuntime());
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
        StartupCommand command = StartupCommand.START;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("-h".equals(arg) || "--help".equals(arg)) {
                return new StartupOptions(null, command, true, false, null);
            }

            if ("-v".equals(arg) || "--version".equals(arg)) {
                return new StartupOptions(null, command, false, true, null);
            }

            if ("-T".equals(arg) || "--test".equals(arg) || "--check-config".equals(arg) || "--check-errors".equals(arg)) {
                command = StartupCommand.TEST;
                continue;
            }
            if ("-R".equals(arg) || "--reload".equals(arg)) {
                command = StartupCommand.RELOAD;
                continue;
            }
            if ("--service-install".equals(arg)) {
                command = StartupCommand.SERVICE_INSTALL;
                continue;
            }
            if ("--service-uninstall".equals(arg)) {
                command = StartupCommand.SERVICE_UNINSTALL;
                continue;
            }
            if ("--service-start".equals(arg)) {
                command = StartupCommand.SERVICE_START;
                continue;
            }
            if ("--service-stop".equals(arg)) {
                command = StartupCommand.SERVICE_STOP;
                continue;
            }
            if ("--service-status".equals(arg)) {
                command = StartupCommand.SERVICE_STATUS;
                continue;
            }

            if ("-c".equals(arg) || "--config".equals(arg)) {
                if (i + 1 >= args.length) {
                    return new StartupOptions(null, command, false, false, "起動オプションエラー: " + arg + " に設定ファイルパスが指定されていません");
                }
                String optionValue = args[++i].trim();
                if (optionValue.isEmpty()) {
                    return new StartupOptions(null, command, false, false, "起動オプションエラー: " + arg + " の設定ファイルパスが空です");
                }
                configPath = optionValue;
                continue;
            }

            if (arg.startsWith("--config=")) {
                String optionValue = arg.substring("--config=".length()).trim();
                if (optionValue.isEmpty()) {
                    return new StartupOptions(null, command, false, false, "起動オプションエラー: --config= の設定ファイルパスが空です");
                }
                configPath = optionValue;
                continue;
            }

            // 後方互換: 先頭の非オプション引数を設定ファイルパスとして扱う
            if (!arg.startsWith("-") && configPath == null) {
                configPath = arg;
                continue;
            }

            return new StartupOptions(null, command, false, false, "起動オプションエラー: 未対応の引数です: " + arg);
        }

        return new StartupOptions(configPath, command, false, false, null);
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
        System.out.println("  -T, --test            設定書式チェックとエラー診断を実行して終了");
        System.out.println("  -R, --reload          実行中エージェントへ設定リロードを要求して終了");
        System.out.println("      --service-install   Windowsサービスを登録（NSSM必須）");
        System.out.println("      --service-uninstall Windowsサービス登録を解除");
        System.out.println("      --service-start     Windowsサービスを起動");
        System.out.println("      --service-stop      Windowsサービスを停止");
        System.out.println("      --service-status    Windowsサービス状態を表示");
        System.out.println("  -v, --version         バージョンのみ表示");
        System.out.println("  -h, --help            このヘルプを表示");
        System.out.println("  [設定ファイルパス]     既存互換の位置引数（先頭1つ）");
        System.out.println("既定設定ファイル: " + defaultConfigPath);
    }

    /**
     * 設定ファイル書式をチェックする
     */
    private static boolean checkConfigFormat(String configPath) {
        AgentLogger.info("設定書式チェックを開始します: " + configPath);
        AgentConfig config = new AgentConfig(configPath);
        boolean valid = config.validateConfigFormat();
        if (valid) {
            AgentLogger.info("設定書式チェック結果: OK");
            return true;
        }
        AgentLogger.error("設定書式チェック結果: NG");
        return false;
    }

    /**
     * 設定および接続のエラー診断を実行する
     */
    private static boolean checkErrors(String configPath) {
        AgentLogger.info("エラー診断を開始します: " + configPath);
        AgentConfig config = new AgentConfig(configPath);
        List<String> diagnostics = new ArrayList<>();

        if (!config.load()) {
            diagnostics.add("設定読み込みエラー: 設定内容を適用できません");
        } else {
            if (config.getNginxLogPaths().isEmpty()) {
                diagnostics.add("設定エラー: logging.servers が空です");
            }
            if (config.getApiKey() == null || config.getApiKey().isBlank()) {
                diagnostics.add("設定エラー: edamame.apiKey が空です");
            }
            if (!config.testTcpConnection()) {
                diagnostics.add("接続エラー: Edamameサーバーへ接続できません");
            }
        }

        if (diagnostics.isEmpty()) {
            AgentLogger.info("エラー診断結果: 問題は検出されませんでした");
            return true;
        }

        AgentLogger.error("エラー診断結果: " + diagnostics.size() + " 件の問題を検出しました");
        for (String diagnostic : diagnostics) {
            AgentLogger.error(" - " + diagnostic);
        }
        return false;
    }

    /**
     * 設定書式チェックとエラー診断を連続実行する
     */
    private static boolean runTestCommand(String configPath) {
        boolean formatOk = checkConfigFormat(configPath);
        boolean diagnosticsOk = checkErrors(configPath);
        return formatOk && diagnosticsOk;
    }

    /**
     * 実行中エージェントへ設定リロードを要求する
     */
    private static boolean triggerReloadCommand(boolean isWindows) {
        try {
            Path reloadCommandPath = resolveReloadCommandPath(isWindows);
            Path parent = reloadCommandPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reloadCommandPath, String.valueOf(System.currentTimeMillis()));
            AgentLogger.info("設定リロード要求を送信しました: " + reloadCommandPath);
            return true;
        } catch (Exception e) {
            AgentLogger.error("設定リロード要求の送信に失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * Windowsサービスを操作する
     */
    private static boolean manageWindowsService(StartupCommand command, String configPath) {
        if (!System.getProperty("os.name", "unknown").toLowerCase().contains("win")) {
            AgentLogger.error("Windowsサービス操作はWindows環境でのみ利用できます");
            return false;
        }

        try {
            if (command == StartupCommand.SERVICE_INSTALL) {
                return installWindowsService(configPath);
            }
            if (command == StartupCommand.SERVICE_UNINSTALL) {
                return runCommand(List.of("nssm", "remove", WINDOWS_SERVICE_NAME, "confirm"), "サービス登録解除");
            }
            if (command == StartupCommand.SERVICE_START) {
                return runCommand(List.of("sc", "start", WINDOWS_SERVICE_NAME), "サービス起動");
            }
            if (command == StartupCommand.SERVICE_STOP) {
                return runCommand(List.of("sc", "stop", WINDOWS_SERVICE_NAME), "サービス停止");
            }
            if (command == StartupCommand.SERVICE_STATUS) {
                return runCommand(List.of("sc", "query", WINDOWS_SERVICE_NAME), "サービス状態確認");
            }
            AgentLogger.error("未対応のサービス操作コマンドです: " + command);
            return false;
        } catch (Exception e) {
            AgentLogger.error("Windowsサービス操作に失敗しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * Windowsサービスを登録する
     */
    private static boolean installWindowsService(String configPath) {
        Path launcher = resolveWindowsLauncherPath();
        if (launcher == null) {
            AgentLogger.error("サービス登録に失敗しました: ランチャーが見つかりません");
            return false;
        }

        Path appDirectory = launcher.getParent();
        String resolvedConfigPath = Paths.get(configPath).toAbsolutePath().normalize().toString();

        if (!runCommand(
            List.of("nssm", "install", WINDOWS_SERVICE_NAME, launcher.toString(), "--config", resolvedConfigPath),
            "サービス登録"
        )) {
            return false;
        }

        boolean displayNameOk = runCommand(
            List.of("nssm", "set", WINDOWS_SERVICE_NAME, "DisplayName", WINDOWS_SERVICE_DISPLAY_NAME),
            "サービス表示名設定"
        );
        boolean appDirectoryOk = true;
        if (appDirectory != null) {
            appDirectoryOk = runCommand(
                List.of("nssm", "set", WINDOWS_SERVICE_NAME, "AppDirectory", appDirectory.toString()),
                "サービス作業ディレクトリ設定"
            );
        }
        boolean startModeOk = runCommand(
            List.of("nssm", "set", WINDOWS_SERVICE_NAME, "Start", "SERVICE_DEMAND_START"),
            "サービス起動モード設定"
        );

        if (!displayNameOk || !appDirectoryOk || !startModeOk) {
            AgentLogger.warn("サービス登録は完了しましたが、一部プロパティ設定に失敗しました");
            return false;
        }

        AgentLogger.info("Windowsサービスを登録しました（初期状態: 手動起動）");
        return true;
    }

    /**
     * 外部コマンドを実行して結果をログ出力する
     */
    private static boolean runCommand(List<String> command, String commandLabel) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                AgentLogger.info(commandLabel + "に成功しました");
                if (!output.isEmpty()) {
                    AgentLogger.info(output.toString().trim());
                }
                return true;
            }
            AgentLogger.error(commandLabel + "に失敗しました (exitCode=" + exitCode + ")");
            if (!output.isEmpty()) {
                AgentLogger.error(output.toString().trim());
            }
            return false;
        } catch (Exception e) {
            AgentLogger.error(commandLabel + "実行中に例外が発生しました: " + e.getMessage());
            return false;
        }
    }

    /**
     * メインエントリーポイント
     */
    public static void main(String[] args) {
        String osName = System.getProperty("os.name", "unknown");
        boolean isWindows = osName.toLowerCase().contains("win");
        String defaultConfigPath = isWindows ? resolveWindowsSystemConfigPath() : "agent-config.json";
        if (!isWindows && Files.exists(Paths.get(LINUX_SYSTEM_CONFIG_PATH))) {
            defaultConfigPath = LINUX_SYSTEM_CONFIG_PATH;
        }
        if (isWindows) {
            setupWindowsCommandAlias();
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
        if (startupOptions.command() == StartupCommand.TEST) {
            boolean ok = runTestCommand(configPath);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.RELOAD) {
            boolean ok = triggerReloadCommand(isWindows);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.SERVICE_INSTALL) {
            boolean ok = manageWindowsService(StartupCommand.SERVICE_INSTALL, configPath);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.SERVICE_UNINSTALL) {
            boolean ok = manageWindowsService(StartupCommand.SERVICE_UNINSTALL, configPath);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.SERVICE_START) {
            boolean ok = manageWindowsService(StartupCommand.SERVICE_START, configPath);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.SERVICE_STOP) {
            boolean ok = manageWindowsService(StartupCommand.SERVICE_STOP, configPath);
            System.exit(ok ? 0 : 1);
            return;
        }
        if (startupOptions.command() == StartupCommand.SERVICE_STATUS) {
            boolean ok = manageWindowsService(StartupCommand.SERVICE_STATUS, configPath);
            System.exit(ok ? 0 : 1);
            return;
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

    /**
     * Windows用のシステム設定ファイルパスを解決する
     */
    private static String resolveWindowsSystemConfigPath() {
        String programDataPath = System.getenv(WINDOWS_PROGRAM_DATA_DIR);
        if (programDataPath == null || programDataPath.isBlank()) {
            programDataPath = WINDOWS_FALLBACK_PROGRAM_DATA_PATH;
        }
        return Paths.get(programDataPath, WINDOWS_SYSTEM_CONFIG_PATH).toString();
    }

    /**
     * OSに応じたリロードコマンドファイルパスを解決する
     */
    private static Path resolveReloadCommandPath(boolean isWindows) {
        if (isWindows) {
            String programDataPath = System.getenv(WINDOWS_PROGRAM_DATA_DIR);
            if (programDataPath == null || programDataPath.isBlank()) {
                programDataPath = WINDOWS_FALLBACK_PROGRAM_DATA_PATH;
            }
            return Paths.get(programDataPath, WINDOWS_RELOAD_COMMAND_PATH).toAbsolutePath().normalize();
        }
        return Paths.get(LINUX_RELOAD_COMMAND_PATH).toAbsolutePath().normalize();
    }

    /**
     * Windows向けにensaaコマンドをセットアップする
     */
    private static void setupWindowsCommandAlias() {
        if (!System.getProperty("os.name", "unknown").toLowerCase().contains("win")) {
            return;
        }

        try {
            String programDataPath = System.getenv(WINDOWS_PROGRAM_DATA_DIR);
            if (programDataPath == null || programDataPath.isBlank()) {
                programDataPath = WINDOWS_FALLBACK_PROGRAM_DATA_PATH;
            }

            Path commandDir = Paths.get(programDataPath, WINDOWS_COMMAND_DIR);
            Files.createDirectories(commandDir);

            Path launcher = resolveWindowsLauncherPath();
            if (launcher == null) {
                AgentLogger.warn("ensaaコマンドのセットアップをスキップしました: ランチャーが見つかりません");
                return;
            }

            Path commandFile = commandDir.resolve(WINDOWS_COMMAND_FILE_NAME);
            String commandContent = "@echo off\r\n\"" + launcher + "\" %*\r\n";
            Files.writeString(commandFile, commandContent);
            AgentLogger.info("Windowsコマンドラッパーを作成しました: " + commandFile);

            if (appendPathIfMissing(commandDir.toString(), "Machine")) {
                AgentLogger.info("ensaaコマンドディレクトリをシステムPATHへ追加しました");
            } else if (appendPathIfMissing(commandDir.toString(), "User")) {
                AgentLogger.info("ensaaコマンドディレクトリをユーザーPATHへ追加しました");
            } else {
                AgentLogger.warn("ensaaコマンドディレクトリのPATH追加に失敗しました");
            }
        } catch (Exception e) {
            AgentLogger.warn("ensaaコマンドのセットアップに失敗しました: " + e.getMessage());
        }
    }

    /**
     * Windows用ランチャー実行ファイルのパスを解決する
     */
    private static Path resolveWindowsLauncherPath() {
        Path javaHome = Paths.get(System.getProperty("java.home", ".")).toAbsolutePath().normalize();
        Path installDir = javaHome.getFileName() != null
            && "runtime".equalsIgnoreCase(javaHome.getFileName().toString())
            ? javaHome.getParent()
            : javaHome;
        if (installDir == null) {
            return null;
        }

        List<Path> candidates = List.of(
            installDir.resolve("EdamameAgent.exe"),
            installDir.resolve("edamame-agent.exe"),
            installDir.resolve("ensaa.exe")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * PATHに対象ディレクトリが無い場合のみ追加する
     */
    private static boolean appendPathIfMissing(String directoryPath, String scope) {
        String escapedPath = directoryPath.replace("'", "''");
        String escapedScope = scope.replace("'", "''");
        String script =
            "$target='" + escapedPath + "'; " +
            "$scope='" + escapedScope + "'; " +
            "$current=[Environment]::GetEnvironmentVariable('Path',$scope); " +
            "if ([string]::IsNullOrEmpty($current)) { $current='' }; " +
            "$normalized=$current.ToLower(); " +
            "if (-not $normalized.Contains($target.ToLower())) { " +
            "  $newPath=($current.TrimEnd(';') + ';' + $target).Trim(';'); " +
            "  [Environment]::SetEnvironmentVariable('Path',$newPath,$scope) " +
            "}";

        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            AgentLogger.debug("PATH更新に失敗しました(scope=" + scope + "): " + e.getMessage());
            return false;
        }
    }
}
