package com.edamame.agent.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Edamame Agent専用ログユーティリティクラス
 * メインアプリケーションと統一されたログフォーマットを提供
 * フォーマット: [yyyy-MM-dd HH:mm:ss][レベル] メッセージ
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class AgentLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // デバッグモードフラグ（動的に変更可能）
    private static boolean debugEnabled = false;

    /**
     * デバッグモードを設定
     * 
     * @param enabled デバッグモードを��効にするかどうか
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /**
     * INFOレベルのログを出力
     *
     * @param message ログメッセージ
     */
    public static void info(String message) {
        log(message, "INFO");
    }

    /**
     * WARNレベルのログを出力
     *
     * @param message ログメッセージ
     */
    public static void warn(String message) {
        log(message, "WARN");
    }

    /**
     * ERRORレベルのログを出力
     *
     * @param message ログメッセージ
     */
    public static void error(String message) {
        log(message, "ERROR");
    }

    /**
     * ERRORレベルのログを例外情報と共に出力
     *
     * @param message ログメッセージ
     * @param throwable 例外情報
     */
    public static void error(String message, Throwable throwable) {
        log(message + ": " + throwable.getMessage(), "ERROR");
    }

    /**
     * DEBUGレベルのログを出力
     * 設定ファイルのdebugModeまたは環境変数EDAMAME_AGENT_DEBUGがtrueの場合のみ出力
     *
     * @param message ログメッセージ
     */
    public static void debug(String message) {
        // 設定ファイルのデバッグモードまたは環境変数をチェック
        String envDebug = System.getenv("EDAMAME_AGENT_DEBUG");
        boolean envEnabled = "true".equalsIgnoreCase(envDebug);
        
        if (debugEnabled || envEnabled) {
            log(message, "DEBUG");
        }
    }

    /**
     * タイムスタンプ＋ログレベル付きで標準出力に出す共通関数
     *
     * @param message メッセージ
     * @param level ログレベル
     */
    private static void log(String message, String level) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(TIMESTAMP_FORMATTER);
        System.out.printf("[%s][%s] %s%n", timestamp, level, message);
    }
}
