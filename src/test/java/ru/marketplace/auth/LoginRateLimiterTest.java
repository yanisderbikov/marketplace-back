package ru.marketplace.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    private static void assertLimited(String message, Runnable action) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertEquals(message, e.getReason());
    }

    @Test
    void allowsLimitPerHourThenAnswers429() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 10, clock);

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> limiter.acquire("203.0.113.7"));
        }
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire("203.0.113.7"));
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire("203.0.113.7"));
    }

    @Test
    void addressesAreCountedSeparately() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 10, clock);

        limiter.acquire("203.0.113.7");
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire("203.0.113.7"));
        assertDoesNotThrow(() -> limiter.acquire("198.51.100.1"));
    }

    @Test
    void windowIsFixedFromFirstRequest() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 10, clock);

        limiter.acquire("203.0.113.7");
        clock.advance(Duration.ofMinutes(59));
        limiter.acquire("203.0.113.7");
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire("203.0.113.7"));

        // Час с первого запроса прошёл — счётчик начинается заново
        clock.advance(Duration.ofMinutes(1));
        assertDoesNotThrow(() -> limiter.acquire("203.0.113.7"));
        assertDoesNotThrow(() -> limiter.acquire("203.0.113.7"));
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire("203.0.113.7"));
    }

    @Test
    void requestsWithoutAddressShareOneCounter() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 10, clock);

        limiter.acquire(null);
        assertLimited(LoginRateLimiter.LIMIT_MESSAGE, () -> limiter.acquire(" "));
    }

    @Test
    void verifyHasItsOwnLimitAndCounter() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 2, clock);

        limiter.acquire("203.0.113.7");
        // Запросы кода не тратят лимит проверок и наоборот
        limiter.acquireVerify("203.0.113.7");
        limiter.acquireVerify("203.0.113.7");
        assertLimited(LoginRateLimiter.VERIFY_LIMIT_MESSAGE, () -> limiter.acquireVerify("203.0.113.7"));
        assertDoesNotThrow(() -> limiter.acquireVerify("198.51.100.1"));

        clock.advance(Duration.ofHours(1));
        assertDoesNotThrow(() -> limiter.acquireVerify("203.0.113.7"));
    }

    @Test
    void nonPositiveLimitFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new LoginRateLimiter(0, 10, clock));
        assertThrows(IllegalStateException.class, () -> new LoginRateLimiter(10, 0, clock));
    }
}
