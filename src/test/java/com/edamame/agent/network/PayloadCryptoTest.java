package com.edamame.agent.network;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadCryptoTest {

    @Test
    void encryptToEnvelopeAndDecryptIfNeededShouldRoundTrip() throws Exception {
        String apiKey = "unit-test-secret-key";
        String plain = "{\"message\":\"hello\"}";

        String encrypted = PayloadCrypto.encryptToEnvelope(plain, apiKey);

        assertTrue(encrypted.startsWith("ENCv1:"));
        assertNotEquals(plain, encrypted);
        assertEquals(plain, PayloadCrypto.decryptIfNeeded(encrypted, apiKey));
    }

    @Test
    void decryptIfNeededShouldFailWhenPayloadIsNotEncrypted() {
        String plain = "{\"status\":\"ok\"}";
        assertThrows(GeneralSecurityException.class,
            () -> PayloadCrypto.decryptIfNeeded(plain, "any-api-key"));
    }

    @Test
    void decryptIfNeededShouldFailWithDifferentApiKey() throws Exception {
        String encrypted = PayloadCrypto.encryptToEnvelope("{\"value\":1}", "correct-key");
        assertThrows(GeneralSecurityException.class,
            () -> PayloadCrypto.decryptIfNeeded(encrypted, "wrong-key"));
    }
}
