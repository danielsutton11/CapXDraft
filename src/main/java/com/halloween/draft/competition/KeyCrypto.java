package com.halloween.draft.competition;

import com.halloween.draft.config.BotProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for stored Torn API keys. The AES key is derived from the
 * KEY_ENCRYPTION_SECRET variable - changing that secret makes stored keys unreadable.
 */
@Component
public class KeyCrypto {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final BotProperties props;
    private final SecureRandom random = new SecureRandom();

    public KeyCrypto(BotProperties props) {
        this.props = props;
    }

    public boolean configured() {
        return props.getKeyEncryptionSecret() != null && props.getKeyEncryptionSecret().length() >= 16;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Key encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Key decryption failed - was KEY_ENCRYPTION_SECRET changed?", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(props.getKeyEncryptionSecret().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }
}