package ru.marketplace.cdek;

import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.Shop;

/**
 * Ключи API СДЭК конкретного магазина: client_id = Account, client_secret = Secure password
 * из личного кабинета СДЭК. Аккаунта платформы нет — доставку мерчант подключает по своему договору,
 * поэтому авторизация, калькулятор и справочник ПВЗ получают ключи аргументом.
 * Секрет только в памяти на время запроса: в БД лежит шифртекст (SecretCipher), в логи не попадает.
 */
public record CdekCredentials(String clientId, String clientSecret) {

    public CdekCredentials {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Не задан Account СДЭК");
        }
        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new IllegalStateException("Не задан Secure password СДЭК");
        }
    }

    /** Ключи СДЭК сохранены у магазина (без расшифровки — для статуса готовности и выбора аккаунтов). */
    public static boolean isConnected(Shop shop) {
        return shop.getCdekClientId() != null && !shop.getCdekClientId().isBlank()
                && shop.getCdekClientSecretEnc() != null && !shop.getCdekClientSecretEnc().isBlank();
    }

    /**
     * Ключи магазина: Account как есть, Secure password — расшифровка cdekClientSecretEnc.
     * IllegalStateException, если СДЭК не подключён или шифртекст не расшифровывается
     * (например, сменили PAYMENT_SECRETS_KEY).
     */
    public static CdekCredentials of(Shop shop, SecretCipher secretCipher) {
        if (!isConnected(shop)) {
            throw new IllegalStateException("У магазина " + shop.getSlug() + " не подключён СДЭК");
        }
        return new CdekCredentials(shop.getCdekClientId(), secretCipher.decrypt(shop.getCdekClientSecretEnc()));
    }

    /** Record по умолчанию печатает все поля — секрет СДЭК в логах недопустим. */
    @Override
    public String toString() {
        return "CdekCredentials[clientId=" + clientId + ", clientSecret=***]";
    }
}
