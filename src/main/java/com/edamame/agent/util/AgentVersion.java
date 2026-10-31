package com.edamame.agent.util;

import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * エージェントのバージョン情報を提供するユーティリティ
 *
 * バージョンは build.gradle の version を唯一のソースとして、
 * マニフェストまたはリソースファイル経由で取得する。
 *
 * @author Edamame Team
 * @version build.gradle の version を参照
 */
public final class AgentVersion {

    private static final Logger LOGGER = Logger.getLogger(AgentVersion.class.getName());
    private static final String UNKNOWN_VERSION = "unknown";
    private static final String VERSION = resolveVersion();

    private AgentVersion() {
        // ユーティリティクラスのためインスタンス化禁止
    }

    /**
     * エージェントのバージョン文字列を返す。
     *
     * @return バージョン文字列
     */
    public static String get() {
        return VERSION;
    }

    /**
     * マニフェストからバージョンを解決する。
     *
     * @return 解決済みバージョン
     */
    private static String resolveVersion() {
        Package pkg = AgentVersion.class.getPackage();
        if (pkg != null) {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion;
            }
        }
        String resourceVersion = resolveVersionFromResource();
        if (resourceVersion != null) {
            return resourceVersion;
        }
        return UNKNOWN_VERSION;
    }

    /**
     * クラスパス上のバージョン定義リソースから取得する。
     *
     * @return バージョン文字列（取得できない場合はnull）
     */
    private static String resolveVersionFromResource() {
        try (InputStream inputStream = AgentVersion.class.getClassLoader()
            .getResourceAsStream("agent-version.properties")) {
            if (inputStream == null) {
                return null;
            }

            Properties properties = new Properties();
            properties.load(inputStream);
            String version = properties.getProperty("agent.version");
            if (version == null || version.isBlank()) {
                return null;
            }
            return version;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "バージョン情報リソースの読み込みに失敗しました", e);
            return null;
        }
    }
}
