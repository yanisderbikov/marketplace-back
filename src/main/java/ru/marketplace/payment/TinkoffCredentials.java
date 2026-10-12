package ru.marketplace.payment;

import ru.marketplace.common.SecretCipher;
import ru.marketplace.model.Shop;

/**
 * Кред терминала Т-Банка конкретного магазина. В anyforms терминал был один на всё приложение
 * (из env), здесь у каждого мерчанта свой — поэтому TinkoffClient получает его аргументом.
 * Пароль только в памяти на время запроса: в БД лежит шифртекст (SecretCipher), в логи не попадает.
 */
public record TinkoffCredentials(String terminalKey, String password) {

    public TinkoffCredentials {
        if (terminalKey == null || terminalKey.isBlank()) {
            throw new IllegalStateException("Не задан TerminalKey терминала Т-Банка");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Не задан пароль терминала Т-Банка");
        }
    }

    /**
     * Кред терминала магазина: TerminalKey как есть, пароль — расшифровка tinkoffPasswordEnc.
     * IllegalStateException, если терминал не настроен или шифртекст не расшифровывается
     * (например, сменили PAYMENT_SECRETS_KEY).
     */
    public static TinkoffCredentials of(Shop shop, SecretCipher secretCipher) {
        String passwordEnc = shop.getTinkoffPasswordEnc();
        if (shop.getTinkoffTerminalKey() == null || shop.getTinkoffTerminalKey().isBlank()
                || passwordEnc == null || passwordEnc.isBlank()) {
            throw new IllegalStateException("У магазина " + shop.getSlug() + " не настроен терминал Т-Банка");
        }
        return new TinkoffCredentials(shop.getTinkoffTerminalKey(), secretCipher.decrypt(passwordEnc));
    }

    /** Record по умолчанию печатает все поля — пароль терминала в логах недопустим. */
    @Override
    public String toString() {
        return "TinkoffCredentials[terminalKey=" + terminalKey + ", password=***]";
    }
}
