package ru.marketplace.shop;

import org.junit.jupiter.api.Test;
import ru.marketplace.model.PaymentProvider;
import ru.marketplace.model.Shop;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopReadinessTest {

    private static Shop readyShop() {
        Shop shop = new Shop();
        shop.setActive(true);
        shop.setTinkoffTerminalKey("1700000000000DEMO");
        shop.setTinkoffPasswordEnc("enc");
        shop.setCdekFromPvzCode("MSK123");
        shop.setCdekClientId("cdek-account");
        shop.setCdekClientSecretEnc("enc");
        shop.setSellerName("ИП Иванов Иван Иванович");
        shop.setSellerInn("123456789012");
        return shop;
    }

    @Test
    void readyShopAcceptsOrders() {
        Shop shop = readyShop();
        assertEquals(List.of(), ShopReadiness.missing(shop));
        assertTrue(ShopReadiness.acceptsOrders(shop));
    }

    @Test
    void deliveryNeedsOwnCdekAccountNotOnlySenderPvz() {
        Shop noAccount = readyShop();
        noAccount.setCdekClientId(null);
        assertEquals(List.of(ShopReadiness.DELIVERY), ShopReadiness.missing(noAccount));

        Shop noSecret = readyShop();
        noSecret.setCdekClientSecretEnc(" ");
        assertEquals(List.of(ShopReadiness.DELIVERY), ShopReadiness.missing(noSecret));
        assertFalse(ShopReadiness.acceptsOrders(noSecret));
    }

    @Test
    void newShopMissesEverythingInFixedOrder() {
        Shop shop = new Shop();
        shop.setActive(true);
        assertEquals(List.of(ShopReadiness.PAYMENT, ShopReadiness.DELIVERY, ShopReadiness.SELLER),
                ShopReadiness.missing(shop));
        assertFalse(ShopReadiness.acceptsOrders(shop));
    }

    @Test
    void paymentNeedsBothTerminalKeyAndPassword() {
        Shop noPassword = readyShop();
        noPassword.setTinkoffPasswordEnc(null);
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(noPassword));

        Shop blankKey = readyShop();
        blankKey.setTinkoffTerminalKey("  ");
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(blankKey));
    }

    @Test
    void receiptWithTaxOutsideCurrentTBankListIsNotReadyForPayment() {
        Shop stale = readyShop();
        stale.setTinkoffReceiptEnabled(true);
        stale.setTinkoffTaxation("osn");
        stale.setTinkoffTax("vat20");
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(stale));

        Shop noTaxation = readyShop();
        noTaxation.setTinkoffReceiptEnabled(true);
        noTaxation.setTinkoffTax("vat22");
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(noTaxation));

        Shop valid = readyShop();
        valid.setTinkoffReceiptEnabled(true);
        valid.setTinkoffTaxation("osn");
        valid.setTinkoffTax("vat22");
        assertEquals(List.of(), ShopReadiness.missing(valid));

        // Чек выключен — сохранённые налоги не мешают приёму заказов
        Shop receiptOff = readyShop();
        receiptOff.setTinkoffReceiptEnabled(false);
        receiptOff.setTinkoffTax("vat20");
        assertEquals(List.of(), ShopReadiness.missing(receiptOff));
    }

    @Test
    void deliveryNeedsPvzCode() {
        Shop shop = readyShop();
        shop.setCdekFromPvzCode("");
        assertEquals(List.of(ShopReadiness.DELIVERY), ShopReadiness.missing(shop));
    }

    @Test
    void sellerNeedsNameAndInn() {
        Shop noInn = readyShop();
        noInn.setSellerInn(null);
        assertEquals(List.of(ShopReadiness.SELLER), ShopReadiness.missing(noInn));

        Shop noName = readyShop();
        noName.setSellerName(" ");
        assertEquals(List.of(ShopReadiness.SELLER), ShopReadiness.missing(noName));
    }

    @Test
    void ogrnAndAddressAreOptional() {
        Shop shop = readyShop();
        shop.setSellerOgrn(null);
        shop.setSellerAddress(null);
        assertTrue(ShopReadiness.acceptsOrders(shop));
    }

    @Test
    void inactiveShopDoesNotAcceptOrdersEvenIfConfigured() {
        Shop shop = readyShop();
        shop.setActive(false);
        assertEquals(List.of(), ShopReadiness.missing(shop));
        assertFalse(ShopReadiness.acceptsOrders(shop));
    }

    @Test
    void yookassaAloneIsEnoughForPayment() {
        Shop shop = readyShop();
        shop.setTinkoffTerminalKey(null);
        shop.setTinkoffPasswordEnc(null);
        shop.setYookassaShopId("123456");
        shop.setYookassaSecretKeyEnc("enc");
        assertEquals(List.of(), ShopReadiness.missing(shop));
        assertEquals(PaymentProvider.YOOKASSA, ShopPayment.activeProvider(shop));

        shop.setYookassaSecretKeyEnc(null);
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(shop));
    }

    @Test
    void readinessChecksReceiptOfActiveProviderOnly() {
        Shop shop = readyShop();
        shop.setYookassaShopId("123456");
        shop.setYookassaSecretKeyEnc("enc");
        shop.setYookassaReceiptEnabled(true);
        shop.setYookassaTax("vat20");
        // Активен Т-Банк (выбран явно) — устаревший чек ЮKassa приёму заказов не мешает
        shop.setPaymentProvider(PaymentProvider.TINKOFF);
        assertEquals(List.of(), ShopReadiness.missing(shop));

        shop.setPaymentProvider(PaymentProvider.YOOKASSA);
        assertEquals(List.of(ShopReadiness.PAYMENT), ShopReadiness.missing(shop));
    }

    @Test
    void chosenButDisconnectedProviderFallsBackToConnectedOne() {
        Shop shop = readyShop();
        shop.setPaymentProvider(PaymentProvider.YOOKASSA);
        assertEquals(PaymentProvider.TINKOFF, ShopPayment.activeProvider(shop));
        assertEquals(List.of(), ShopReadiness.missing(shop));
    }
}
