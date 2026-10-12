package ru.marketplace.order;

import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.OrderItem;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;

import java.time.Instant;
import java.util.UUID;

/** Заказы и магазины для тестов модуля заказов. */
final class OrderTestData {

    static final UUID SHOP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private OrderTestData() {
    }

    static Shop shop() {
        Shop shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setSlug("candles");
        shop.setName("Свечная лавка");
        shop.setActive(true);
        shop.setBrandColor("#7f95a7");
        shop.setTinkoffReceiptEnabled(false);
        return shop;
    }

    static Order order(Long id, String publicId, PaymentStatus status, Shop shop) {
        Order order = new Order();
        order.setId(id);
        order.setPublicId(publicId);
        order.setShop(shop);
        order.setPaymentStatus(status);
        order.setFulfillmentStatus(FulfillmentStatus.NEW);
        order.setCustomerName("Анна Петрова");
        order.setCustomerPhone("+7 999 123-45-67");
        order.setCustomerEmail("anna@example.com");
        order.setPvzCode("MSK123");
        order.setPvzCity("Москва");
        order.setPvzAddress("ул. Тверская, 1");
        order.setMarketingConsent(false);
        order.setItemsTotalKopecks(0L);
        order.setPaidNotified(false);
        order.setCreatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        order.setUpdatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        if (status == PaymentStatus.PAID) {
            order.setPaidAt(Instant.parse("2026-10-01T09:05:00Z"));
        }
        return order;
    }

    /** Добавляет позицию и пересчитывает itemsTotalKopecks, как это делает оформление заказа. */
    static OrderItem addItem(Order order, Long itemId, String name, long unitPriceKopecks, int quantity) {
        OrderItem item = OrderItem.builder()
                .id(itemId)
                .order(order)
                .productId(UUID.nameUUIDFromBytes(name.getBytes()))
                .productName(name)
                .unitPriceKopecks(unitPriceKopecks)
                .quantity(quantity)
                .build();
        order.getItems().add(item);
        order.setItemsTotalKopecks(order.getItemsTotalKopecks() + unitPriceKopecks * quantity);
        return item;
    }
}
