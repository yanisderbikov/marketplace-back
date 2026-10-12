package ru.marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PublicRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T09:00:00Z"));
    private final PublicRateLimiter limiter = new PublicRateLimiter(clock);

    private static void assertLimitedAfter(int limit, String message, Consumer<String> acquire) {
        for (int i = 0; i < limit; i++) {
            acquire.accept("203.0.113.7");
        }
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> acquire.accept("203.0.113.7"));
        assertEquals(429, e.getStatusCode().value());
        assertEquals(message, e.getReason());
        assertDoesNotThrow(() -> acquire.accept("198.51.100.1"));
    }

    @Test
    void checkoutIsLimitedTo30PerHour() {
        assertLimitedAfter(30, PublicRateLimiter.CHECKOUT_LIMIT, limiter::checkout);
        clock.advance(Duration.ofHours(1));
        assertDoesNotThrow(() -> limiter.checkout("203.0.113.7"));
    }

    @Test
    void deliveryCostIsLimitedTo120PerHour() {
        assertLimitedAfter(120, PublicRateLimiter.DELIVERY_COST_LIMIT, limiter::deliveryCost);
    }

    @Test
    void orderStatusIsLimitedTo600PerHour() {
        assertLimitedAfter(600, PublicRateLimiter.ORDER_STATUS_LIMIT, limiter::orderStatus);
    }

    @Test
    void endpointsHaveSeparateCounters() {
        for (int i = 0; i < 30; i++) {
            limiter.checkout("203.0.113.7");
        }
        assertDoesNotThrow(() -> limiter.deliveryCost("203.0.113.7"));
        assertDoesNotThrow(() -> limiter.orderStatus("203.0.113.7"));
    }
}
