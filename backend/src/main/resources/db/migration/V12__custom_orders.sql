ALTER TABLE crypto_order ADD COLUMN trigger_price NUMERIC(20,4);
ALTER TABLE crypto_order ADD COLUMN trigger_dir VARCHAR(5);
ALTER TABLE crypto_order ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'AI';

CREATE INDEX idx_crypto_order_status ON crypto_order (status);
