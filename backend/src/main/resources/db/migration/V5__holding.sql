CREATE TABLE holding (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type VARCHAR(10) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    units NUMERIC(24,8) NOT NULL,
    invested NUMERIC(16,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_holding_user ON holding (user_id);
