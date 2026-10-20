ALTER TABLE holding ALTER COLUMN invested DROP NOT NULL;

CREATE TABLE coindcx_link (
    user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    api_key_enc TEXT NOT NULL,
    api_secret_enc TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
