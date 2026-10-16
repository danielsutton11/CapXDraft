CREATE TABLE IF NOT EXISTS faction_draft_state (
                                                   faction_key TEXT PRIMARY KEY,
                                                   state       JSONB       NOT NULL,
                                                   updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

-- Faction API keys for attack monitoring. The key itself is AES-GCM encrypted.
CREATE TABLE IF NOT EXISTS faction_api_keys (
                                                id              BIGSERIAL PRIMARY KEY,
                                                faction_key     TEXT        NOT NULL,
                                                owner_torn_id   BIGINT      NOT NULL,
                                                owner_name      TEXT        NOT NULL,
                                                encrypted_key   TEXT        NOT NULL,
                                                added_by        BIGINT      NOT NULL,
                                                added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_ok_at      TIMESTAMPTZ,
    last_error      TEXT,
    UNIQUE (faction_key, owner_torn_id)
    );

-- Every outgoing attack seen during a competition, stored once by Torn attack id.
CREATE TABLE IF NOT EXISTS competition_attacks (
                                                   faction_key       TEXT   NOT NULL,
                                                   attack_id         BIGINT NOT NULL,
                                                   attacker_id       BIGINT,
                                                   defender_id       BIGINT,
                                                   defender_faction  BIGINT,
                                                   result            TEXT   NOT NULL,
                                                   started           BIGINT NOT NULL,
                                                   ended             BIGINT NOT NULL,
                                                   PRIMARY KEY (faction_key, attack_id)
    );
CREATE INDEX IF NOT EXISTS competition_attacks_ended ON competition_attacks (faction_key, ended);