package com.halloween.draft.competition;

import com.halloween.draft.config.BotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * KEY_ENCRYPTION_SECRET variable.
 *
 * <p>Rotation: set KEY_ENCRYPTION_SECRET to the new secret and KEY_ENCRYPTION_SECRET_OLD to the
 * previous one. New keys are always encrypted with the new secret; stored keys that only the old
 * secret can read still decrypt, and {@link KeyRotationJob} re-encrypts them on startup. Once the
 * job logs that nothing is left on the old secret, remove KEY_ENCRYPTION_SECRET_OLD.
 */
@Component
public class KeyCrypto {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final BotProperties props;
    private final String oldSecret;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public KeyCrypto(BotProperties props, @Value("${KEY_ENCRYPTION_SECRET_OLD:}") String oldSecret) {
        this.props = props;
        this.oldSecret = oldSecret == null || oldSecret.isBlank() ? null : oldSecret;
    }

    /** No old secret - for tests and anything that builds this by hand. */
    public KeyCrypto(BotProperties props) {
        this(props, null);
    }

    public boolean configured() {
        return props.getKeyEncryptionSecret() != null && props.getKeyEncryptionSecret().length() >= 16;
    }

    /** True when an old secret is set and differs from the current one. */
    public boolean rotating() {
        return oldSecret != null && configured() && !oldSecret.equals(props.getKeyEncryptionSecret());
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(props.getKeyEncryptionSecret()), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Key encryption failed", e);
        }
    }

    /** Decrypts with the current secret, falling back to the old one while rotating. */
    public String decrypt(String stored) {
        String plain = tryDecrypt(stored, props.getKeyEncryptionSecret());
        if (plain == null && rotating()) {
            plain = tryDecrypt(stored, oldSecret);
        }
        if (plain == null) {
            throw new IllegalStateException(rotating()
                    ? "Key decryption failed with both KEY_ENCRYPTION_SECRET and KEY_ENCRYPTION_SECRET_OLD"
                    : "Key decryption failed - was KEY_ENCRYPTION_SECRET changed?");
        }
        return plain;
    }

    /** Outcome of re-encrypting one stored value. */
    public enum Rotation { ALREADY_CURRENT, ROTATED, UNREADABLE }

    public record RotationResult(Rotation outcome, String newValue) {}

    /**
     * Re-encrypts a value that only the old secret can read. Values the current secret already
     * reads are left alone, so running this repeatedly is safe.
     */
    public RotationResult rotate(String stored) {
        if (tryDecrypt(stored, props.getKeyEncryptionSecret()) != null) {
            return new RotationResult(Rotation.ALREADY_CURRENT, null);
        }
        String plain = rotating() ? tryDecrypt(stored, oldSecret) : null;
        if (plain == null) {
            return new RotationResult(Rotation.UNREADABLE, null);
        }
        return new RotationResult(Rotation.ROTATED, encrypt(plain));
    }

    /** Returns null when this secret can't decrypt the value (wrong secret or corrupt data). */
    private static String tryDecrypt(String stored, String secret) {
        if (stored == null || secret == null || secret.isEmpty()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKeySpec key(String secret) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }
}