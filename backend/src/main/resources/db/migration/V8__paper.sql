CREATE TABLE paper_account (
    user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    cash NUMERIC(14,2) NOT NULL,
    start_cash NUMERIC(14,2) NOT NULL,
    start_benchmark NUMERIC(14,4),
    last_decision_on DATE,
    last_attempt_at TIMESTAMP,
    last_summary TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE paper_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    symbol VARCHAR(32) NOT NULL,
    qty BIGINT NOT NULL,
    avg_cost NUMERIC(14,4) NOT NULL,
    UNIQUE (user_id, symbol)
);

CREATE TABLE paper_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(4) NOT NULL,
    qty BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    reason TEXT,
    decided_on DATE NOT NULL,
    fill_price NUMERIC(14,4),
    fees NUMERIC(12,2),
    filled_on DATE
);

CREATE INDEX idx_paper_order_user ON paper_order (user_id, id DESC);

CREATE TABLE paper_snapshot (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    day DATE NOT NULL,
    equity NUMERIC(14,2) NOT NULL,
    benchmark NUMERIC(14,4),
    UNIQUE (user_id, day)
);
