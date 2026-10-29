package com.edamame.agent.network;

/**
 * TCP通信プロトコル定数
 *
 * サーバー側（AgentTcpServer）とエージェント側（LogTransmitter, ServerRegistration）で
 * 共通使用するプロトコル定数を定義
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public final class TcpProtocolConstants {

    private TcpProtocolConstants() {
        // ユーティリティクラスのため、インスタンス化を禁止
    }

    // TCP通信用のメッセージタイプ定数
    public static final byte MSG_TYPE_LOG_BATCH = 0x01;
    public static final byte MSG_TYPE_HEARTBEAT = 0x02;
    public static final byte MSG_TYPE_BLOCK_REQUEST = 0x03;
    public static final byte MSG_TYPE_AUTH = 0x04;

    // 接続テスト用（エージェント設定読み込み時の軽量テスト）
    public static final byte MSG_TYPE_CONNECTION_TEST = 0x09;

    // サーバー登録関連
    public static final byte MSG_TYPE_REGISTER = 0x10;
    public static final byte MSG_TYPE_UNREGISTER = 0x11;
    public static final byte MSG_TYPE_BLOCK_RESULT = 0x13;

    // レガシー（廃止予定）
    @Deprecated
    public static final byte MSG_TYPE_HEARTBEAT_REGISTER = 0x12;

    // TCP通信用のレスポンスコード
    public static final byte RESPONSE_SUCCESS = 0x00;
    public static final byte RESPONSE_ERROR = 0x01;
    public static final byte RESPONSE_AUTH_FAILED = 0x02;

    // 有効なAPIキー（実際の運用では外部設定から読み込み）
    public static final String VALID_API_KEY = "edamame-agent-api-key-2025";

    // 通信制限設定
    public static final int SOCKET_TIMEOUT = 30000; // 30秒
    public static final int MAX_MESSAGE_SIZE = 10 * 1024 * 1024; // 10MB
    public static final long CONNECTION_TIMEOUT = 300000; // 5分間の非活動でタイムアウト
}
