ALTER TABLE paper_account ADD COLUMN strategy TEXT;

CREATE TABLE crypto_account (
    user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    cash NUMERIC(16,2) NOT NULL,
    start_cash NUMERIC(16,2) NOT NULL,
    start_benchmark NUMERIC(18,4),
    last_decision_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    last_summary TEXT,
    auto_trade BOOLEAN NOT NULL DEFAULT FALSE,
    strategy TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE crypto_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    symbol VARCHAR(16) NOT NULL,
    qty NUMERIC(28,10) NOT NULL,
    avg_cost NUMERIC(20,4) NOT NULL,
    UNIQUE (user_id, symbol)
);

CREATE TABLE crypto_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    symbol VARCHAR(16) NOT NULL,
    side VARCHAR(4) NOT NULL,
    qty NUMERIC(28,10),
    amount_inr NUMERIC(16,2) NOT NULL,
    status VARCHAR(10) NOT NULL,
    reason TEXT,
    decided_on DATE NOT NULL,
    fill_price NUMERIC(20,4),
    fees NUMERIC(14,2)
);

CREATE INDEX idx_crypto_order_user ON crypto_order (user_id, id DESC);

CREATE TABLE crypto_snapshot (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    day DATE NOT NULL,
    equity NUMERIC(16,2) NOT NULL,
    benchmark NUMERIC(18,4),
    UNIQUE (user_id, day)
);
