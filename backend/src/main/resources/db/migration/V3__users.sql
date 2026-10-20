CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    google_sub VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    picture VARCHAR(1024),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE expense ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;

CREATE INDEX idx_expense_user_spent_on ON expense (user_id, spent_on);
