package io.github.illuseahashmap.serviceregistry.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ServiceSecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String masterKeyBase64;

    public ServiceSecretCipher(String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt service secret", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith("v1:")) {
            throw new IllegalStateException("Unsupported service secret format");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(3));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalStateException("Invalid service secret payload");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt service secret", exception);
        }
    }

    private byte[] masterKey() {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            throw new IllegalStateException("Service secret encryption key is not configured");
        }
        byte[] key = Base64.getDecoder().decode(masterKeyBase64);
        if (key.length != 32) {
            throw new IllegalStateException("Service secret encryption key must contain 32 bytes");
        }
        return key;
    }
}
