package com.edamame.agent.network;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * APIキーを用いたアプリ層ペイロード暗号化ユーティリティ
 *
 * @author Edamame Team
 * @version build.gradle の version を参照
 */
public final class PayloadCrypto {

    private static final String ENVELOPE_PREFIX = "ENCv1:";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PayloadCrypto() {
        // ユーティリティクラスのため、インスタンス化を禁止
    }

    /**
     * 平文文字列を暗号化エンベロープへ変換する
     *
     * @param plainText 暗号化対象の平文
     * @param apiKey 鍵導出に使用するAPIキー
     * @return ENCv1プレフィックス付きの暗号化文字列
     * @throws GeneralSecurityException 暗号化に失敗した場合
     */
    public static String encryptToEnvelope(String plainText, String apiKey) throws GeneralSecurityException {
        SecretKeySpec keySpec = deriveKey(apiKey);
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] packed = new byte[nonce.length + cipherText.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(cipherText, 0, packed, nonce.length, cipherText.length);

        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(packed);
    }

    /**
     * ENCv1形式の暗号文を復号する（暗号化必須）。
     *
     * @param value 復号対象文字列（ENCv1形式）
     * @param apiKey 鍵導出に使用するAPIキー
     * @return 復号済み平文
     * @throws GeneralSecurityException 復号に失敗した場合
     */
    public static String decryptIfNeeded(String value, String apiKey) throws GeneralSecurityException {
        if (value == null || !value.startsWith(ENVELOPE_PREFIX)) {
            throw new GeneralSecurityException("暗号化されていないペイロードを受信しました");
        }

        SecretKeySpec keySpec = deriveKey(apiKey);
        byte[] packed = Base64.getDecoder().decode(value.substring(ENVELOPE_PREFIX.length()));
        if (packed.length <= GCM_NONCE_LENGTH) {
            throw new GeneralSecurityException("暗号文フォーマットが不正です");
        }

        byte[] nonce = Arrays.copyOfRange(packed, 0, GCM_NONCE_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(packed, GCM_NONCE_LENGTH, packed.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        byte[] plainBytes = cipher.doFinal(cipherText);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    private static SecretKeySpec deriveKey(String apiKey) throws GeneralSecurityException {
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        if (trimmedKey.isEmpty() || "CHANGE_ME".equals(trimmedKey)) {
            throw new GeneralSecurityException("apiKey が未設定のため暗号化できません");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(trimmedKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
