package ru.marketplace.shop;

import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.Shop;

/**
 * Какие платёжки у магазина подключены и через какую витрина принимает оплату — одна функция
 * для готовности, чекаута и кабинета. Подключено = ключи на месте и чек (если включён) настроен
 * налогами из актуального перечня.
 */
public final class ShopPayment {

    private ShopPayment() {
    }

    public static boolean tinkoffConnected(Shop shop) {
        return !isBlank(shop.getTinkoffTerminalKey()) && !isBlank(shop.getTinkoffPasswordEnc());
    }

    public static boolean yookassaConnected(Shop shop) {
        return !isBlank(shop.getYookassaShopId()) && !isBlank(shop.getYookassaSecretKeyEnc());
    }

    public static boolean connected(Shop shop, PaymentProvider provider) {
        return switch (provider) {
            case TINKOFF -> tinkoffConnected(shop);
            case YOOKASSA -> yookassaConnected(shop);
        };
    }

    /**
     * Провайдер, через которого витрина принимает оплату: выбранный мерчантом, если он подключён,
     * иначе — любой подключённый (Т-Банк первым: магазины до ЮKassa выбор не сохраняли). null — нечем.
     */
    public static PaymentProvider activeProvider(Shop shop) {
        PaymentProvider chosen = shop.getPaymentProvider();
        if (chosen != null && connected(shop, chosen)) {
            return chosen;
        }
        if (tinkoffConnected(shop)) {
            return PaymentProvider.TINKOFF;
        }
        return yookassaConnected(shop) ? PaymentProvider.YOOKASSA : null;
    }

    /** Активный провайдер есть, и его чек (если включён) настроен — витрина может принять деньги. */
    public static boolean ready(Shop shop) {
        PaymentProvider provider = activeProvider(shop);
        if (provider == null) {
            return false;
        }
        return switch (provider) {
            case TINKOFF -> receiptSettingsValid(shop.getTinkoffReceiptEnabled(), shop.getTinkoffTaxation(),
                    shop.getTinkoffTax());
            case YOOKASSA -> receiptSettingsValid(shop.getYookassaReceiptEnabled(), shop.getYookassaTaxation(),
                    shop.getYookassaTax());
        };
    }

    /**
     * Чек включён — налоги должны быть из актуального перечня. Сохранённое раньше значение, которого
     * в перечне больше нет (например vat20), — это «оплата не настроена» с плашкой в админке,
     * а не 502 на каждом платеже у покупателя.
     */
    private static boolean receiptSettingsValid(Boolean receiptEnabled, String taxation, String tax) {
        if (!Boolean.TRUE.equals(receiptEnabled)) {
            return true;
        }
        // Set.of(...).contains(null) бросает NPE — null проверяем сами
        return taxation != null && ShopServiceImpl.TAXATIONS.contains(taxation)
                && tax != null && ShopServiceImpl.TAXES.contains(tax);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
