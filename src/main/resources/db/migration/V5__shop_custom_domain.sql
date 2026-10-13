-- Свой домен витрины (shop.example.ru → витрина магазина без /shop/{slug}). Хранится в нижнем регистре
-- и в punycode (кириллические .рф-домены — xn--…), без www: www.{домен} резолвится в тот же магазин.
-- Один домен — один магазин: по нему гейтвей решает, чью витрину отдавать и выпускать ли сертификат.
ALTER TABLE shop ADD COLUMN custom_domain VARCHAR(253);
CREATE UNIQUE INDEX ux_shop_custom_domain ON shop(custom_domain);
