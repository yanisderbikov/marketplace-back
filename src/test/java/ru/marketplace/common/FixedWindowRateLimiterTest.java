package ru.marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T09:00:00Z"));

    @Test
    void limitPerKeyWithinWindowThen429WithMessage() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(10), "Хватит", clock);

        limiter.acquire("a");
        limiter.acquire("a");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> limiter.acquire("a"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertEquals("Хватит", e.getReason());
        assertDoesNotThrow(() -> limiter.acquire("b"));

        clock.advance(Duration.ofMinutes(10));
        assertDoesNotThrow(() -> limiter.acquire("a"));
    }

    @Test
    void nonPositiveLimitFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new FixedWindowRateLimiter(0, Duration.ofHours(1), "x", clock));
    }
}
