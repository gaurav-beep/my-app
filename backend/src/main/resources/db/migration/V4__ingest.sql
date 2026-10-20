ALTER TABLE app_user ADD COLUMN ingest_token_hash VARCHAR(64);
CREATE UNIQUE INDEX ux_app_user_ingest_token ON app_user (ingest_token_hash) WHERE ingest_token_hash IS NOT NULL;

ALTER TABLE expense ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'manual';
ALTER TABLE expense ADD COLUMN external_ref VARCHAR(64);
CREATE UNIQUE INDEX ux_expense_user_ref ON expense (user_id, external_ref) WHERE external_ref IS NOT NULL;
