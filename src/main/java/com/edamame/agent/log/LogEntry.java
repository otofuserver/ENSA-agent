package com.edamame.agent.log;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ログエントリデータクラス
 *
 * 収集したNginxログの情報を保持する
 * Java 11-21対応のRecordクラス
 * Jackson LocalDateTime問題回避のため文字列型に変更
 *
 * @author Edamame Team
 * @version 1.2.0
 */
public record LogEntry(
    String clientIp,
    String timestamp,
    String request,
    int statusCode,
    String responseSize,
    String referer,
    String userAgent,
    String sourcePath,
    String serverName,
    String logTag,
    String collectedAt,  // LocalDateTime → Stringに変更
    boolean blockedByModSec  // ModSecurityブロックフラグを追加
) {

    /**
     * JSONシリアライゼーション用のコンストラクタ
     */
    public LogEntry {
        // レコードの不変性を保証
        if (clientIp == null) clientIp = "";
        if (timestamp == null) timestamp = "";
        if (request == null) request = "";
        if (responseSize == null) responseSize = "-";
        if (referer == null) referer = "";
        if (userAgent == null) userAgent = "";
        if (sourcePath == null) sourcePath = "";
        if (serverName == null) serverName = "";
        if (logTag == null || logTag.isBlank()) logTag = "nginx";
        if (collectedAt == null) collectedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 現在時刻でLogEntryを作成するファクトリメソッド
     */
    public static LogEntry createWithCurrentTime(
        String clientIp,
        String timestamp,
        String request,
        int statusCode,
        String responseSize,
        String referer,
        String userAgent,
        String sourcePath,
        String serverName
    ) {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return new LogEntry(clientIp, timestamp, request, statusCode, responseSize,
                           referer, userAgent, sourcePath, serverName, "nginx", currentTime, false);
    }

    /**
     * ModSecurityブロック付きでLogEntryを作成するファクトリメソッド
     */
    public static LogEntry createModSecurityBlocked(
        String clientIp,
        String timestamp,
        String request,
        int statusCode,
        String responseSize,
        String referer,
        String userAgent,
        String sourcePath,
        String serverName
    ) {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return new LogEntry(clientIp, timestamp, request, statusCode, responseSize,
                           referer, userAgent, sourcePath, serverName, "nginx", currentTime, true);
    }

    /**
     * HTTPメソッドを抽出
     *
     * @return HTTPメソッド（GET, POST, etc.）
     */
    public String getHttpMethod() {
        if (request == null || request.isEmpty()) {
            return "UNKNOWN";
        }

        String[] parts = request.split(" ");
        return parts.length > 0 ? parts[0] : "UNKNOWN";
    }

    /**
     * リクエストURLを抽出
     *
     * @return リクエストURL
     */
    public String getRequestUrl() {
        if (request == null || request.isEmpty()) {
            return "/";
        }

        String[] parts = request.split(" ");
        return parts.length > 1 ? parts[1] : "/";
    }

    /**
     * HTTPプロトコルバージョンを抽出
     *
     * @return HTTPプロトコルバージョン
     */
    public String getHttpVersion() {
        if (request == null || request.isEmpty()) {
            return "HTTP/1.1";
        }

        String[] parts = request.split(" ");
        return parts.length > 2 ? parts[2] : "HTTP/1.1";
    }

    /**
     * エラーレスポンスかどうかを判定
     *
     * @return エラーレスポンスの場合true
     */
    public boolean isErrorResponse() {
        return statusCode >= 400;
    }

    /**
     * サーバーエラーかどうかを判定
     *
     * @return サーバーエラーの場合true
     */
    public boolean isServerError() {
        return statusCode >= 500;
    }

    /**
     * クライアントエラーかどうかを判定
     *
     * @return クライアントエラーの場合true
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * レスポンスサイズを数値で取得
     *
     * @return レスポンスサイズ（バイト）、不明な場合は0
     */
    public long getResponseSizeBytes() {
        if ("-".equals(responseSize) || responseSize == null || responseSize.isEmpty()) {
            return 0L;
        }

        try {
            return Long.parseLong(responseSize);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 収集時刻を文字列形式で取得
     *
     * @return ISO形式の日時文字列
     */
    public String getCollectedAtString() {
        return collectedAt;
    }

    /**
     * ログエントリの要約文字列を生成
     *
     * @return 要約文字列
     */
    public String getSummary() {
        return String.format("%s %s %s %d %s",
            clientIp,
            getHttpMethod(),
            getRequestUrl(),
            statusCode,
            responseSize
        );
    }

    /**
     * JSON形式の文字列表現（デバッグ用）
     *
     * @return JSON形式の文字列
     */
    @Override
    public String toString() {
        return String.format(
            "{\"clientIp\":\"%s\",\"timestamp\":\"%s\",\"request\":\"%s\",\"statusCode\":%d,\"responseSize\":\"%s\",\"serverName\":\"%s\",\"logTag\":\"%s\",\"collectedAt\":\"%s\"}",
            clientIp, timestamp, request, statusCode, responseSize, serverName, logTag, getCollectedAtString()
        );
    }
}
