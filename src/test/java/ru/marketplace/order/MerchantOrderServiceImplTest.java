package ru.marketplace.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.common.PageDTO;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.order.dto.FulfillmentUpdateRequestDTO;
import ru.marketplace.order.dto.MerchantOrderDTO;
import ru.marketplace.repository.OrderRepository;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static ru.marketplace.order.OrderTestData.SHOP_ID;
import static ru.marketplace.order.OrderTestData.addItem;
import static ru.marketplace.order.OrderTestData.order;

class MerchantOrderServiceImplTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderRowLockRepository lockRepository = mock(OrderRowLockRepository.class);
    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final Shop shop = OrderTestData.shop();
    private MerchantOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MerchantOrderServiceImpl(orderRepository, lockRepository, currentMerchant);
        when(currentMerchant.shop()).thenReturn(shop);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lockRepository.findOwnForUpdate(anyString(), any())).thenReturn(Optional.empty());
    }

    /** Репозиторий отдаёт content одной страницей с тем Pageable, что ему передали. */
    private void stubPage(List<Order> content) {
        when(orderRepository.findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(any(), any(), any(), any()))
                .thenAnswer(inv -> new PageImpl<>(content, inv.<Pageable>getArgument(3), content.size()));
    }

    private static FulfillmentUpdateRequestDTO request(String status, String tracking) {
        FulfillmentUpdateRequestDTO dto = new FulfillmentUpdateRequestDTO();
        dto.setFulfillmentStatus(status);
        dto.setTrackingNumber(tracking);
        return dto;
    }

    // ---------- список ----------

    @Test
    @SuppressWarnings("unchecked")
    void listIsScopedToOwnShopWithFiltersAndNewestFirst() {
        Order paid = order(1L, "AAA111", PaymentStatus.PAID, shop);
        addItem(paid, 2L, "Подсвечник", 120_000, 1);
        addItem(paid, 1L, "Свеча", 50_000, 2);
        stubPage(List.of(paid));

        PageDTO<MerchantOrderDTO> page = service.list("PAID", "ALL", 0, 20);

        ArgumentCaptor<Collection<PaymentStatus>> payments = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<FulfillmentStatus>> fulfillments = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(
                eq(SHOP_ID), payments.capture(), fulfillments.capture(), pageable.capture());
        assertEquals(Set.of(PaymentStatus.PAID), Set.copyOf(payments.getValue()));
        assertEquals(EnumSet.allOf(FulfillmentStatus.class), Set.copyOf(fulfillments.getValue()));
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("createdAt").getDirection());

        assertEquals(1, page.total());
        assertEquals(0, page.page());
        assertEquals(20, page.size());
        MerchantOrderDTO dto = page.items().get(0);
        assertEquals("AAA111", dto.publicId());
        assertEquals(PaymentStatus.PAID, dto.paymentStatus());
        assertEquals(FulfillmentStatus.NEW, dto.fulfillmentStatus());
        assertEquals("Анна Петрова", dto.customerName());
        assertEquals("MSK123", dto.pvzCode());
        assertEquals(220_000, dto.itemsTotalKopecks());
        // Позиции в порядке оформления (по id), а не в порядке списка
        assertEquals(List.of("Свеча", "Подсвечник"), dto.items().stream().map(i -> i.productName()).toList());
        assertEquals(2, dto.items().get(0).quantity());
        assertEquals(50_000, dto.items().get(0).unitPriceKopecks());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allAndLowercaseFiltersAreAccepted() {
        stubPage(List.of());

        service.list("all", " shipped ", 1, 10);

        ArgumentCaptor<Collection<PaymentStatus>> payments = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<FulfillmentStatus>> fulfillments = ArgumentCaptor.forClass(Collection.class);
        verify(orderRepository).findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(
                eq(SHOP_ID), payments.capture(), fulfillments.capture(), any());
        assertEquals(EnumSet.allOf(PaymentStatus.class), Set.copyOf(payments.getValue()));
        assertEquals(Set.of(FulfillmentStatus.SHIPPED), Set.copyOf(fulfillments.getValue()));
    }

    @Test
    void pageSizeIsCappedAt100() {
        stubPage(List.of());

        PageDTO<MerchantOrderDTO> page = service.list("PAID", "ALL", 0, 5000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(any(), any(), any(), pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(100, page.size());
    }

    @Test
    void invalidPaymentFilterIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.list("PAYED", "ALL", 0, 20));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("Некорректный фильтр оплаты: PAYED", e.getReason());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void invalidFulfillmentFilterIs400() {
        // CANCELED — статус оплаты; у исполнения он пишется CANCELLED
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.list("PAID", "CANCELED", 0, 20));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void negativePageAndZeroSizeAre400() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.list("PAID", "ALL", -1, 20)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.list("PAID", "ALL", 0, 0)).getStatusCode());
        verifyNoInteractions(orderRepository);
    }

    // ---------- смена исполнения ----------

    @Test
    void updatesOwnPaidOrder() {
        Order paid = order(1L, "AAA111", PaymentStatus.PAID, shop);
        addItem(paid, 1L, "Свеча", 50_000, 1);
        when(lockRepository.findOwnForUpdate("AAA111", SHOP_ID)).thenReturn(Optional.of(paid));

        MerchantOrderDTO dto = service.updateFulfillment(" aaa111 ", request("shipped", "  1234567890  "));

        assertEquals(FulfillmentStatus.SHIPPED, paid.getFulfillmentStatus());
        assertEquals("1234567890", paid.getTrackingNumber());
        verify(orderRepository).save(paid);
        assertEquals(FulfillmentStatus.SHIPPED, dto.fulfillmentStatus());
        assertEquals("1234567890", dto.trackingNumber());
        assertEquals(1, dto.items().size());
    }

    @Test
    void blankTrackingClearsIt() {
        Order paid = order(1L, "AAA111", PaymentStatus.PAID, shop);
        paid.setTrackingNumber("OLD");
        when(lockRepository.findOwnForUpdate("AAA111", SHOP_ID)).thenReturn(Optional.of(paid));

        MerchantOrderDTO dto = service.updateFulfillment("AAA111", request("DONE", "   "));

        assertNull(paid.getTrackingNumber());
        assertNull(dto.trackingNumber());
        assertEquals(FulfillmentStatus.DONE, paid.getFulfillmentStatus());
    }

    @Test
    void foreignOrUnknownOrderIs404() {
        // Заказ другого магазина репозиторий с фильтром по shopId просто не находит
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.updateFulfillment("ZZZ999", request("SHIPPED", null)));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(lockRepository).findOwnForUpdate("ZZZ999", SHOP_ID);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void notPaidOrderIs409AndUnchanged() {
        for (PaymentStatus status : List.of(PaymentStatus.AWAITING_PAYMENT, PaymentStatus.FAILED, PaymentStatus.CANCELED)) {
            Order order = order(1L, "AAA111", status, shop);
            when(lockRepository.findOwnForUpdate("AAA111", SHOP_ID)).thenReturn(Optional.of(order));

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.updateFulfillment("AAA111", request("SHIPPED", "123")));

            assertEquals(HttpStatus.CONFLICT, e.getStatusCode(), status.name());
            assertEquals(FulfillmentStatus.NEW, order.getFulfillmentStatus());
            assertNull(order.getTrackingNumber());
        }
        verify(orderRepository, never()).save(any());
    }

    @Test
    void invalidStatusOrTooLongTrackingIs400BeforeLookup() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateFulfillment("AAA111", request("SENT", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateFulfillment("AAA111", request("ALL", null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateFulfillment("AAA111", request(null, null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.updateFulfillment("AAA111", request("SHIPPED", "1".repeat(65)))).getStatusCode());
        verifyNoInteractions(lockRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void trackingOf64CharsIsAccepted() {
        Order paid = order(1L, "AAA111", PaymentStatus.PAID, shop);
        when(lockRepository.findOwnForUpdate("AAA111", SHOP_ID)).thenReturn(Optional.of(paid));

        service.updateFulfillment("AAA111", request("SHIPPED", "1".repeat(64)));

        assertEquals(64, paid.getTrackingNumber().length());
    }

    @Test
    void pageMapsTotalsFromSpringPage() {
        Page<Order> springPage = new PageImpl<>(List.of(), PageRequest.of(2, 10), 25);
        when(orderRepository.findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(any(), any(), any(), any()))
                .thenReturn(springPage);

        PageDTO<MerchantOrderDTO> page = service.list("PAID", "ALL", 2, 10);

        assertEquals(25, page.total());
        assertEquals(2, page.page());
        assertEquals(10, page.size());
    }
}
