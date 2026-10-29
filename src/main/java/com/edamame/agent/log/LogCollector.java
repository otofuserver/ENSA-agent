package com.edamame.agent.log;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.util.AgentLogger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Nginxログ収集クラス
 * ホストまたはDockerコンテナのNginxログファイルを監視し、
 * 新しいログエントリを収集する
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class LogCollector {

    private static final String POSITION_FILE = getPositionFilePath();

    private final AgentConfig config;
    private final Map<String, Long> filePositions;
    private final Map<String, Boolean> rotatedAfterLastRead = new HashMap<>();
    private final Pattern nginxLogPattern;

    /**
     * コンストラクタ
     *
     * @param config エージェント設定
     */
    public LogCollector(AgentConfig config) {
        this.config = config;
        this.filePositions = new HashMap<>();
        this.nginxLogPattern = buildLogPattern(config.getLogFormat());

        loadFilePositions();
        AgentLogger.info("LogCollectorを初期化しました。監視対象ログファイル数: " + config.getNginxLogPaths().size());
    }

    /**
     * Nginxログフォーマットから正規表現パターンを構築
     */
    private Pattern buildLogPattern(String logFormat) {
        // 基本的なNginxログフォーマットの正規表現
        // 必要に応じてカスタマイズ可能
        String pattern = "^(\\S+) \\S+ \\S+ \\[([^\\]]+)\\] \"(\\S+) ([^\"]*) (\\S+)\" (\\d+) (\\d+|-) \"([^\"]*)\" \"([^\"]*)\"";
        return Pattern.compile(pattern);
    }

    /**
     * 新しいログエントリを収集
     *
     * @return 新しいログエントリのリスト
     */
    public List<LogEntry> collectNewLogs() {
        List<LogEntry> logs = new ArrayList<>();

        for (String logPath : config.getNginxLogPaths()) {
            try {
                List<LogEntry> pathLogs = collectLogsFromFile(logPath);
                logs.addAll(pathLogs);
            } catch (Exception e) {
                AgentLogger.warn("ログファイル読み取り中にエラーが発生しました (" + logPath + "): " + e.getMessage());
            }
        }

        if (!logs.isEmpty()) {
            saveFilePositions();
            AgentLogger.debug(logs.size() + " 件の新しいログエントリを収集しました");
        }

        return logs;
    }

    /**
     * 指定されたファイルからログを収集
     */
    private List<LogEntry> collectLogsFromFile(String logPath) throws IOException {
        List<LogEntry> logs = new ArrayList<>();
        Path path = Paths.get(logPath);

        if (!Files.exists(path)) {
            AgentLogger.warn("ログファイルが存在しません: " + logPath);
            return logs;
        }

        long currentPosition = filePositions.getOrDefault(logPath, 0L);
        long fileSize = Files.size(path);

        if (fileSize < currentPosition) {
            // ファイルがローテーションされた可能性
            currentPosition = 0L;
            // ローテート直後フラグが未設定またはfalseのときのみMSG出力
            if (!rotatedAfterLastRead.getOrDefault(logPath, false)) {
                AgentLogger.info("ログファイルのローテーションを検出しました: " + logPath);
                rotatedAfterLastRead.put(logPath, true);
            }
        }

        if (fileSize == currentPosition) {
            // 新しいデータなし
            return logs;
        }

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(currentPosition);
            String line;
            long newPosition = currentPosition;
            boolean hasNewLine = false;

            while ((line = file.readLine()) != null) {
                newPosition = file.getFilePointer();
                LogEntry entry = parseLogLine(line, logPath);
                if (entry != null) {
                    logs.add(entry);
                    hasNewLine = true;
                }
            }

            filePositions.put(logPath, newPosition);
            // 新規行があればローテート直後フラグを解除
            if (hasNewLine) {
                rotatedAfterLastRead.put(logPath, false);
            }
        }

        return logs;
    }

    /**
     * ログ行をパースしてLogEntryに変換
     */
    private LogEntry parseLogLine(String line, String sourcePath) {
        try {
            // ModSecurityエラーログの場合は、サーバー側で処理するため生ログとして送信
            if (isModSecurityErrorLog(line)) {
                return createRawLogEntry(line, sourcePath);
            }
            
            // 通常のNGINXアクセスログの処理
            Matcher matcher = nginxLogPattern.matcher(line);
            if (matcher.find()) {
                String clientIp = matcher.group(1);
                String timestamp = matcher.group(2);
                String method = matcher.group(3);
                String uri = matcher.group(4);
                String protocol = matcher.group(5);
                int status = Integer.parseInt(matcher.group(6));
                String bodyBytesSent = matcher.group(7);
                String referer = matcher.group(8);
                String userAgent = matcher.group(9);

                // HTTPリクエスト文字列を組み立て
                String request = method + " " + uri + " " + protocol;

                // サーバー名を抽出（ログパスから推定）
                String serverName = extractServerName(sourcePath);

                return new LogEntry(
                    clientIp,
                    timestamp,
                    request,
                    status,
                    bodyBytesSent,  // String型のまま渡す
                    referer,
                    userAgent,
                    sourcePath,
                    serverName,
                    "nginx",
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    false  // 通常のアクセスログはModSecurityブロックではない
                );
            }
        } catch (Exception e) {
            AgentLogger.debug("ログ行のパースに失敗しました: " + line + " (エラー: " + e.getMessage() + ")");
        }

        return null;
    }

    /**
     * ModSecurityエラーログかどうかを判定
     */
    private boolean isModSecurityErrorLog(String line) {
        return line.contains("ModSecurity:") && line.contains("Access denied");
    }

    /**
     * ModSecurityエラーログを生ログエントリとして作成
     * サーバー側でModSecurityの詳細解析と適切な関連付けを行うため、生ログラインを送信
     */
    private LogEntry createRawLogEntry(String line, String sourcePath) {
        try {
            // サーバー名を抽出
            String serverName = extractServerName(sourcePath);

            // 生ログエントリとして作成（サーバー側で詳細処理）
            return new LogEntry(
                "",  // clientIp - サーバー側で抽出
                "",  // timestamp - サーバー側で抽出
                line, // request - 生ログラインを格納
                0,   // statusCode - サーバー側で抽出
                "",  // responseSize
                "",  // referer
                "",  // userAgent
                sourcePath,
                serverName,
                "nginx",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                false // blockedByModSec - サーバー側で判定
            );

        } catch (Exception e) {
            AgentLogger.debug("ModSecurity生ログエントリ作成に失敗しました: " + line + " (エラー: " + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * ファイル位置情報を読み込み
     */
    private void loadFilePositions() {
        try {
            Path positionFile = Paths.get(POSITION_FILE);
            if (Files.exists(positionFile)) {
                List<String> lines = Files.readAllLines(positionFile);
                for (String line : lines) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        filePositions.put(parts[0], Long.parseLong(parts[1]));
                    }
                }
                AgentLogger.debug("ファイル位置情報を読み込みました: " + filePositions.size() + " ファイル");
            }
        } catch (Exception e) {
            AgentLogger.warn("ファイル位置情報の読み込みに失敗しました: " + e.getMessage());
        }
    }

    /**
     * ファイル位置情報を保存
     */
    private void saveFilePositions() {
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Long> entry : filePositions.entrySet()) {
                lines.add(entry.getKey() + ":" + entry.getValue());
            }

            Path positionFile = Paths.get(POSITION_FILE);
            Files.write(positionFile, lines);
            AgentLogger.debug("ファイル位置情報を保存しました");

        } catch (Exception e) {
            AgentLogger.warn("ファイル位置情報の保存に失敗しました: " + e.getMessage());
        }
    }

    /**
     * JARと同じディレクトリにファイル位置情報を保存するパスを返す
     */
    private static String getPositionFilePath() {
        try {
            String jarPath = LogCollector.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            java.io.File jarFile = new java.io.File(jarPath);
            String dir = jarFile.getParent();
            if (dir == null) dir = ".";
            return dir + java.io.File.separator + "edamame-agent-positions.txt";
        } catch (Exception e) {
            // 失敗時はカレントディレクトリに保存
            return "edamame-agent-positions.txt";
        }
    }

    /**
     * ログパスからサーバー名を抽出
     */
    private String extractServerName(String logPath) {
        // AgentConfigから設定されたサーバー名を取得（見つからない場合はデフォルト値を返す）
        String serverName = config.getServerNameByLogPath(logPath);
        AgentLogger.debug("ログパス " + logPath + " のサーバー名: " + serverName);
        return serverName;
    }
}