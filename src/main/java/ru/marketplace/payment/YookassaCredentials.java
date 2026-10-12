package ru.marketplace.payment;

import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.Shop;

/**
 * Ключи магазина в ЮKassa: shopId и секретный ключ — HTTP Basic для каждого запроса к API.
 * Как и пароль терминала Т-Банка, ключ только в памяти на время запроса: в БД — шифртекст, в логах — ***.
 */
public record YookassaCredentials(String shopId, String secretKey) {

    public YookassaCredentials {
        if (shopId == null || shopId.isBlank()) {
            throw new IllegalStateException("Не задан shopId ЮKassa");
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException("Не задан секретный ключ ЮKassa");
        }
    }

    /**
     * Ключи магазина: shopId как есть, ключ — расшифровка yookassaSecretKeyEnc. IllegalStateException,
     * если ЮKassa не подключена или шифртекст не расшифровывается (сменили PAYMENT_SECRETS_KEY).
     */
    public static YookassaCredentials of(Shop shop, SecretCipher secretCipher) {
        String keyEnc = shop.getYookassaSecretKeyEnc();
        if (shop.getYookassaShopId() == null || shop.getYookassaShopId().isBlank() || keyEnc == null || keyEnc.isBlank()) {
            throw new IllegalStateException("У магазина " + shop.getSlug() + " не подключена ЮKassa");
        }
        return new YookassaCredentials(shop.getYookassaShopId(), secretCipher.decrypt(keyEnc));
    }

    /** Record по умолчанию печатает все поля — секретный ключ в логах недопустим. */
    @Override
    public String toString() {
        return "YookassaCredentials[shopId=" + shopId + ", secretKey=***]";
    }
}
