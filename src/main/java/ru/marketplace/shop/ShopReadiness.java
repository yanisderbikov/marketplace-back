package ru.marketplace.shop;

import ru.marketplace.cdek.CdekCredentials;
import ru.marketplace.model.Shop;

import java.util.ArrayList;
import java.util.List;

/**
 * Готов ли магазин принимать заказы. Без своего терминала Т-Банка или магазина ЮKassa некуда принять деньги,
 * без своего аккаунта СДЭК и ПВЗ отправки не посчитать доставку, без продавца нечего написать в оферте и подвале.
 * Одна функция на бэк и фронт: фронт показывает плашку по missingForOrders, чекаут отвечает 409.
 */
public final class ShopReadiness {

    public static final String PAYMENT = "PAYMENT", DELIVERY = "DELIVERY", SELLER = "SELLER";

    private ShopReadiness() {
    }

    /** Чего не хватает для приёма заказов; порядок: PAYMENT, DELIVERY, SELLER. */
    public static List<String> missing(Shop shop) {
        List<String> missing = new ArrayList<>(3);
        if (!ShopPayment.ready(shop)) {
            missing.add(PAYMENT);
        }
        if (isBlank(shop.getCdekFromPvzCode()) || !CdekCredentials.isConnected(shop)) {
            missing.add(DELIVERY);
        }
        if (isBlank(shop.getSellerName()) || isBlank(shop.getSellerInn())) {
            missing.add(SELLER);
        }
        return List.copyOf(missing);
    }

    /** Магазин включён и всё настроено. */
    public static boolean acceptsOrders(Shop shop) {
        return Boolean.TRUE.equals(shop.getActive()) && missing(shop).isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
