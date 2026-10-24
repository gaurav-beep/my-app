ALTER TABLE crypto_position DROP CONSTRAINT crypto_position_user_id_symbol_key;
ALTER TABLE crypto_position ADD CONSTRAINT crypto_position_user_symbol_side_key UNIQUE (user_id, symbol, side);
