package com.halloween.draft.core;

import com.halloween.draft.model.FactionState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Each faction's whole state is stored as one JSONB row, so a redeploy resumes mid-draft. */
@Repository
public class StateRepository {
    private static final Logger log = LoggerFactory.getLogger(StateRepository.class);

    /** What startup found: readable states, and the keys of rows that couldn't be read (already backed up). */
    public record LoadResult(Map<String, FactionState> states, List<String> unreadable) {}

    /** Thrown when a saved row exists but can't be turned back into a FactionState. */
    public static class UnreadableState extends RuntimeException {
        public UnreadableState(String key, Throwable cause) {
            super("Saved state for " + key + " couldn't be read", cause);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Reads every faction's state. A row that can't be read is copied to faction_state_backup and
     * reported, never silently replaced.
     */
    public LoadResult loadAll() {
        Map<String, FactionState> out = new HashMap<>();
        List<String> unreadable = new java.util.ArrayList<>();
        jdbc.query("SELECT faction_key, state::text AS state FROM faction_draft_state", rs -> {
            String key = rs.getString("faction_key");
            String raw = rs.getString("state");
            try {
                out.put(key, parse(raw));
            } catch (Exception e) {
                log.error("Could not read saved state for {} - backed up, and the faction is locked until fixed", key, e);
                backup(key, raw, e);
                unreadable.add(key);
            }
        });
        return new LoadResult(out, unreadable);
    }

    /**
     * One faction's last saved state, used to roll back after a failed action.
     * Empty when it has never been saved.
     * @throws UnreadableState if the row exists but can't be read
     */
    public Optional<FactionState> load(String key) {
        List<String> rows = jdbc.query("SELECT state::text FROM faction_draft_state WHERE faction_key = ?",
                (rs, i) -> rs.getString(1), key);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(parse(rows.get(0)));
        } catch (Exception e) {
            backup(key, rows.get(0), e);
            throw new UnreadableState(key, e);
        }
    }

    private FactionState parse(String raw) throws Exception {
        return mapper.readValue(raw, FactionState.class);
    }

    private void backup(String key, String raw, Exception problem) {
        try {
            jdbc.update("""
                    INSERT INTO faction_state_backup (faction_key, state, error)
                    SELECT ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM faction_state_backup WHERE faction_key = ? AND state = ?)
                    """, key, raw, String.valueOf(problem.getMessage()), key, raw);
        } catch (Exception e) {
            log.error("Couldn't back up the unreadable state for {}", key, e);
        }
    }

    public void save(FactionState state) {
        try {
            String json = mapper.writeValueAsString(state);
            jdbc.update("""
                    INSERT INTO faction_draft_state (faction_key, state, updated_at)
                    VALUES (?, ?::jsonb, now())
                    ON CONFLICT (faction_key) DO UPDATE SET state = EXCLUDED.state, updated_at = now()
                    """, state.factionKey, json);
        } catch (Exception e) {
            log.error("Failed to save state for {}", state.factionKey, e);
        }
    }
}
