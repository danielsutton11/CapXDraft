package com.halloween.draft.core;

import com.halloween.draft.model.FactionState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/** Each faction's whole state is stored as one JSONB row, so a redeploy resumes mid-draft. */
@Repository
public class StateRepository {
    private static final Logger log = LoggerFactory.getLogger(StateRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Map<String, FactionState> loadAll() {
        Map<String, FactionState> out = new HashMap<>();
        jdbc.query("SELECT faction_key, state::text AS state FROM faction_draft_state", rs -> {
            String key = rs.getString("faction_key");
            try {
                out.put(key, mapper.readValue(rs.getString("state"), FactionState.class));
            } catch (Exception e) {
                log.error("Could not read saved state for {} - starting it fresh", key, e);
            }
        });
        return out;
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
