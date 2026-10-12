-- Вторая платёжка — ЮKassa. Магазин может подключить и Т-Банк, и ЮKassa, но оплату на витрине
-- принимает через одного провайдера: shop.payment_provider (NULL — выбрать по тому, что подключено).
ALTER TABLE shop ADD COLUMN payment_provider VARCHAR(16);
UPDATE shop SET payment_provider = 'TINKOFF'
WHERE tinkoff_terminal_key IS NOT NULL AND tinkoff_password_enc IS NOT NULL;

-- shopId и секретный ключ из личного кабинета ЮKassa; ключ — шифртекст SecretCipher, как пароль терминала.
-- Настройки чека у ЮKassa свои: онлайн-касса подключается к каждому провайдеру отдельно.
ALTER TABLE shop ADD COLUMN yookassa_shop_id VARCHAR(32);
ALTER TABLE shop ADD COLUMN yookassa_secret_key_enc TEXT;
ALTER TABLE shop ADD COLUMN yookassa_receipt_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shop ADD COLUMN yookassa_taxation VARCHAR(32);
ALTER TABLE shop ADD COLUMN yookassa_tax VARCHAR(16);

-- Заказ помнит, через кого создан платёж: мерчант может сменить провайдера, пока заказ ждёт оплаты,
-- а статус спрашивать надо там, где платёж создан. Id платежа теперь не только Т-Банка.
ALTER TABLE orders ADD COLUMN payment_provider VARCHAR(16) NOT NULL DEFAULT 'TINKOFF';
ALTER TABLE orders RENAME COLUMN tinkoff_payment_id TO payment_id;
CREATE INDEX idx_orders_payment_id ON orders(payment_id);
