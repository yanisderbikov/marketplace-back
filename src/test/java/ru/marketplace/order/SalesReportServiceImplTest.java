package ru.marketplace.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.order.dto.ProductSalesDTO;
import ru.marketplace.order.dto.SalesReportDTO;
import ru.marketplace.repository.OrderRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static ru.marketplace.order.OrderTestData.SHOP_ID;
import static ru.marketplace.order.OrderTestData.addItem;
import static ru.marketplace.order.OrderTestData.order;

class SalesReportServiceImplTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final Shop shop = OrderTestData.shop();
    private SalesReportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SalesReportServiceImpl(orderRepository, currentMerchant);
        when(currentMerchant.shop()).thenReturn(shop);
        when(orderRepository.findPaidByShopBetween(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void daysAreMoscowDaysInclusive() {
        service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));

        // 1 октября 00:00 МСК = 30 сентября 21:00 UTC; конец — начало 8 октября по Москве, не включительно
        verify(orderRepository).findPaidByShopBetween(SHOP_ID,
                Instant.parse("2026-09-30T21:00:00Z"),
                Instant.parse("2026-10-07T21:00:00Z"));
    }

    @Test
    void singleDayCoversWholeMoscowDay() {
        SalesReportDTO report = service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        verify(orderRepository).findPaidByShopBetween(eq(SHOP_ID),
                eq(Instant.parse("2026-09-30T21:00:00Z")),
                eq(Instant.parse("2026-10-01T21:00:00Z")));
        assertEquals(LocalDate.of(2026, 10, 1), report.from());
        assertEquals(LocalDate.of(2026, 10, 1), report.to());
    }

    @Test
    void fromAfterToIs400WithoutQuery() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getReport(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("Дата начала позже даты конца", e.getReason());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void missingDateIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getReport(null, LocalDate.of(2026, 10, 1)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(orderRepository);
    }

    @Test
    void groupsByProductNameAndSortsByTotalDesc() {
        Order first = order(1L, "AAA111", PaymentStatus.PAID, shop);
        addItem(first, 1L, "Свеча «Лаванда»", 50_000, 2);
        addItem(first, 2L, "Подсвечник", 120_000, 1);
        Order second = order(2L, "BBB222", PaymentStatus.PAID, shop);
        addItem(second, 3L, "Свеча «Лаванда»", 50_000, 1);
        addItem(second, 4L, "Спички", 9_950, 3);
        when(orderRepository.findPaidByShopBetween(any(), any(), any())).thenReturn(List.of(first, second));

        SalesReportDTO report = service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertEquals("candles", report.shopSlug());
        assertEquals("Свечная лавка", report.shopName());
        assertEquals(2, report.ordersCount());
        assertEquals(7, report.itemsCount());
        assertEquals(220_000 + 79_850, report.totalKopecks());
        assertEquals(List.of(
                new ProductSalesDTO("Свеча «Лаванда»", 3, 150_000),
                new ProductSalesDTO("Подсвечник", 1, 120_000),
                new ProductSalesDTO("Спички", 3, 29_850)), report.products());
    }

    @Test
    void totalIsSumOfOrderTotalsNotRecomputedFromItems() {
        // Сумма заказа = Amount платежа Т-Банка; отчёт берёт её как есть
        Order paid = order(1L, "AAA111", PaymentStatus.PAID, shop);
        addItem(paid, 1L, "Свеча", 50_000, 1);
        paid.setItemsTotalKopecks(49_900L);
        when(orderRepository.findPaidByShopBetween(any(), any(), any())).thenReturn(List.of(paid));

        SalesReportDTO report = service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertEquals(49_900, report.totalKopecks());
        assertEquals(50_000, report.products().get(0).totalKopecks());
    }

    @Test
    void emptyPeriodGivesZeros() {
        SalesReportDTO report = service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertEquals(0, report.ordersCount());
        assertEquals(0, report.itemsCount());
        assertEquals(0, report.totalKopecks());
        assertEquals(List.of(), report.products());
    }

    @Test
    void noShopYetIsConflictFromCurrentMerchant() {
        when(currentMerchant.shop()).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Сначала создайте магазин"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verifyNoInteractions(orderRepository);
    }
}
