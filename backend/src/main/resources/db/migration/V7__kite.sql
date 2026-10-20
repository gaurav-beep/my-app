ALTER TABLE holding ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'manual';
ALTER TABLE holding ADD COLUMN snap_price NUMERIC(20,6);
ALTER TABLE holding ADD COLUMN snap_at VARCHAR(40);

CREATE TABLE kite_link (
    user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    api_key_enc TEXT NOT NULL,
    api_secret_enc TEXT NOT NULL,
    access_token_enc TEXT,
    token_at TIMESTAMP,
    state VARCHAR(64),
    state_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kite_link_state ON kite_link (state);
