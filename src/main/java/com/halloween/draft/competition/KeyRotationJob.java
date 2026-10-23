package com.halloween.draft.competition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, re-encrypts every stored API key that is still on KEY_ENCRYPTION_SECRET_OLD so it
 * uses KEY_ENCRYPTION_SECRET. Does nothing unless KEY_ENCRYPTION_SECRET_OLD is set. Safe to run on
 * every restart: keys already on the new secret are skipped.
 */
@Component
public class KeyRotationJob implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KeyRotationJob.class);

    private record StoredKey(long id, String factionKey, String ownerName, String encryptedKey) {}

    private final JdbcTemplate jdbc;
    private final KeyCrypto crypto;

    public KeyRotationJob(JdbcTemplate jdbc, KeyCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!crypto.rotating()) return;

        List<StoredKey> keys = jdbc.query(
                "SELECT id, faction_key, owner_name, encrypted_key FROM faction_api_keys ORDER BY id",
                (rs, i) -> new StoredKey(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));

        int rotated = 0, current = 0, unreadable = 0, changedMeanwhile = 0;
        for (StoredKey k : keys) {
            KeyCrypto.RotationResult r = crypto.rotate(k.encryptedKey());
            switch (r.outcome()) {
                case ALREADY_CURRENT -> current++;
                case ROTATED -> {
                    // Only overwrite the exact value we read, in case the key was replaced meanwhile.
                    int n = jdbc.update(
                            "UPDATE faction_api_keys SET encrypted_key = ? WHERE id = ? AND encrypted_key = ?",
                            r.newValue(), k.id(), k.encryptedKey());
                    if (n == 1) rotated++; else changedMeanwhile++;
                }
                case UNREADABLE -> {
                    unreadable++;
                    log.warn("Key rotation: key {} ({}, owner {}) can't be read with either secret - "
                            + "it needs to be removed and re-added", k.id(), k.factionKey(), k.ownerName());
                }
            }
        }

        log.info("Key rotation: {} re-encrypted, {} already on the new secret, {} unreadable, {} changed during the run",
                rotated, current, unreadable, changedMeanwhile);
        if (unreadable == 0) {
            log.info("Key rotation complete - KEY_ENCRYPTION_SECRET_OLD can now be removed");
        }
    }
}