-- Общий потолок неверных кодов на почту за сутки (поверх 5 попыток на один код):
-- без него перебор шёл по 5 попыток на каждый новый код раз в минуту без конца.
-- DEFAULT только для бэкфила существующих строк — дальше значение всегда пишет приложение.
ALTER TABLE login_code ADD COLUMN fail_window_start TIMESTAMPTZ;
ALTER TABLE login_code ADD COLUMN fails_in_window INT NOT NULL DEFAULT 0;
ALTER TABLE login_code ALTER COLUMN fails_in_window DROP DEFAULT;
