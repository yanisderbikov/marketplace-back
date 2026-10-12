package ru.marketplace.payment;

import org.junit.jupiter.api.Test;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.repository.OrderRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingPaymentCheckerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderPaymentService orderPaymentService = mock(OrderPaymentService.class);
    private final PendingPaymentChecker checker = new PendingPaymentChecker(
            orderRepository, orderPaymentService, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Order awaiting(long id) {
        Order order = new Order();
        order.setId(id);
        order.setPublicId("ORDER" + id);
        order.setPaymentStatus(PaymentStatus.AWAITING_PAYMENT);
        order.setPaymentId("pay-" + id);
        return order;
    }

    private void candidates(Order... orders) {
        when(orderRepository.findAwaitingForCheck(NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofSeconds(60))))
                .thenReturn(List.of(orders));
        for (Order order : orders) {
            when(orderPaymentService.claimBankCheck(order.getId(), NOW, Duration.ofSeconds(60))).thenReturn(true);
        }
    }

    @Test
    void failsOrdersAwaitingLongerThanDay() {
        candidates();

        checker.check();

        verify(orderPaymentService).failStaleAwaiting(NOW.minus(Duration.ofHours(24)));
        verify(orderPaymentService, never()).checkWithBank(any(), any());
    }

    @Test
    void asksBankForFreshAwaitingOrdersAndCountsChanges() {
        Order paid = awaiting(1);
        Order expired = awaiting(2);
        Order stillWaiting = awaiting(3);
        candidates(paid, expired, stillWaiting);
        when(orderPaymentService.checkWithBank(paid, NOW)).thenReturn(PaymentStatus.PAID);
        when(orderPaymentService.checkWithBank(expired, NOW)).thenReturn(PaymentStatus.FAILED);
        when(orderPaymentService.checkWithBank(stillWaiting, NOW)).thenReturn(PaymentStatus.AWAITING_PAYMENT);

        assertEquals(2, checker.check());
    }

    @Test
    void oneBrokenOrderDoesNotStopOthers() {
        Order broken = awaiting(1);
        Order paid = awaiting(2);
        candidates(broken, paid);
        when(orderPaymentService.checkWithBank(broken, NOW)).thenThrow(new IllegalStateException("заказ пропал"));
        when(orderPaymentService.checkWithBank(paid, NOW)).thenReturn(PaymentStatus.PAID);

        assertEquals(1, checker.check());

        verify(orderPaymentService).checkWithBank(paid, NOW);
    }

    @Test
    void bankUnavailableIsNotCountedAsChange() {
        Order order = awaiting(1);
        candidates(order);
        when(orderPaymentService.checkWithBank(order, NOW)).thenReturn(null);

        assertEquals(0, checker.check());
    }

    @Test
    void orderClaimedByStatusPageMeanwhileIsSkipped() {
        Order claimedElsewhere = awaiting(1);
        Order free = awaiting(2);
        candidates(claimedElsewhere, free);
        // Страница успеха уже спрашивает банк по первому заказу — второй GetState параллельно не нужен
        when(orderPaymentService.claimBankCheck(1L, NOW, Duration.ofSeconds(60))).thenReturn(false);
        when(orderPaymentService.checkWithBank(free, NOW)).thenReturn(PaymentStatus.PAID);

        assertEquals(1, checker.check());

        verify(orderPaymentService, never()).checkWithBank(claimedElsewhere, NOW);
        verify(orderPaymentService).checkWithBank(free, NOW);
    }

    @Test
    void tickNeverThrows() {
        when(orderPaymentService.failStaleAwaiting(any())).thenThrow(new RuntimeException("БД недоступна"));

        checker.tick();
    }
}
