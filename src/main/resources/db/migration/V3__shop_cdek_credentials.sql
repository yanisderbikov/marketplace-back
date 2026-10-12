-- СДЭК подключает мерчант по своему договору: ключи API (Account / Secure password из личного кабинета СДЭК)
-- у каждого магазина свои, ключей платформы больше нет. Secure password хранится шифртекстом SecretCipher,
-- как пароль терминала Т-Банка.
ALTER TABLE shop ADD COLUMN cdek_client_id VARCHAR(128);
ALTER TABLE shop ADD COLUMN cdek_client_secret_enc TEXT;
