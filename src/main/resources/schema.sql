CREATE TABLE IF NOT EXISTS faction_draft_state (
    faction_key TEXT PRIMARY KEY,
    state       JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
