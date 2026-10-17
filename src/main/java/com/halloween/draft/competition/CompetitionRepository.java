package com.halloween.draft.competition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stored API keys and the attacks seen during competitions. */
@Repository
public class CompetitionRepository {

    /** A stored key. {@code encryptedKey} is only decrypted when making a call. */
    public record ApiKey(long id, String factionKey, long ownerTornId, String ownerName, String encryptedKey,
                         long addedBy, Instant lastOkAt, String lastError) {}

    /** One outgoing attack as returned by Torn. */
    public record AttackRow(long attackId, Long attackerId, Long defenderId, Long defenderFaction,
                            String result, long started, long ended) {}

    private final JdbcTemplate jdbc;

    public CompetitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- keys ----------

    public List<ApiKey> keys(String factionKey) {
        return jdbc.query("""
                SELECT id, faction_key, owner_torn_id, owner_name, encrypted_key, added_by, last_ok_at, last_error
                FROM faction_api_keys WHERE faction_key = ? ORDER BY added_at
                """, (rs, i) -> new ApiKey(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getLong(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getString(8)), factionKey);
    }

    /** Adds a key, or replaces the existing key from the same owner. */
    public void saveKey(String factionKey, long ownerTornId, String ownerName, String encryptedKey, long addedBy) {
        jdbc.update("""
                INSERT INTO faction_api_keys (faction_key, owner_torn_id, owner_name, encrypted_key, added_by, last_ok_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (faction_key, owner_torn_id) DO UPDATE
                    SET owner_name = EXCLUDED.owner_name, encrypted_key = EXCLUDED.encrypted_key,
                        added_by = EXCLUDED.added_by, added_at = now(), last_ok_at = now(), last_error = NULL
                """, factionKey, ownerTornId, ownerName, encryptedKey, addedBy);
    }

    /** Removes every stored key for a faction (Clean Up). Returns how many were removed. */
    public int deleteAllKeys(String factionKey) {
        return jdbc.update("DELETE FROM faction_api_keys WHERE faction_key = ?", factionKey);
    }

    public int deleteKey(String factionKey, long id) {
        return jdbc.update("DELETE FROM faction_api_keys WHERE faction_key = ? AND id = ?", factionKey, id);
    }

    public void markKeyOk(long id) {
        jdbc.update("UPDATE faction_api_keys SET last_ok_at = now(), last_error = NULL WHERE id = ?", id);
    }

    public void markKeyError(long id, String error) {
        jdbc.update("UPDATE faction_api_keys SET last_error = ? WHERE id = ?", error, id);
    }

    // ---------- attacks ----------

    public void saveAttacks(String factionKey, Collection<AttackRow> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO competition_attacks
                    (faction_key, attack_id, attacker_id, defender_id, defender_faction, result, started, ended)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (faction_key, attack_id) DO NOTHING
                """, rows.stream().map(r -> new Object[]{factionKey, r.attackId(), r.attackerId(), r.defenderId(),
                r.defenderFaction(), r.result(), r.started(), r.ended()}).toList());
    }

    /**
     * Successful hits per attacker inside the window, excluding attacks on the given factions.
     * @return attacker Torn ID → hit count
     */
    public Map<Long, Integer> hitsByAttacker(String factionKey, long fromEpoch, long toEpoch,
                                             Collection<String> countedResults, Collection<Long> excludedFactions) {
        String results = String.join(",", countedResults.stream().map(r -> "?").toList());
        String excluded = excludedFactions.isEmpty() ? "" :
                " AND (defender_faction IS NULL OR defender_faction NOT IN ("
                        + String.join(",", excludedFactions.stream().map(x -> "?").toList()) + "))";
        String sql = "SELECT attacker_id, COUNT(*) FROM competition_attacks"
                + " WHERE faction_key = ? AND ended >= ? AND ended < ? AND attacker_id IS NOT NULL"
                + " AND result IN (" + results + ")" + excluded
                + " GROUP BY attacker_id";
        List<Object> args = new java.util.ArrayList<>();
        args.add(factionKey);
        args.add(fromEpoch);
        args.add(toEpoch);
        args.addAll(countedResults);
        args.addAll(excludedFactions);
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getLong(1), rs.getInt(2));
        }, args.toArray());
        return out;
    }

    /** Removes every stored attack for a faction (Clean Up). Returns how many were removed. */
    public int deleteAttacks(String factionKey) {
        return jdbc.update("DELETE FROM competition_attacks WHERE faction_key = ?", factionKey);
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}