package ru.marketplace.storage;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UploadRateLimiterTest {

    @Test
    void limitIsPerShopPerHour() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T09:00:00Z"));
        UploadRateLimiter limiter = new UploadRateLimiter(clock);
        UUID shop = UUID.randomUUID();
        for (int i = 0; i < UploadRateLimiter.PRESIGNS_PER_HOUR; i++) {
            limiter.acquire(shop);
        }

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> limiter.acquire(shop));
        assertEquals(429, e.getStatusCode().value());
        assertEquals(UploadRateLimiter.LIMIT_MESSAGE, e.getReason());
        assertDoesNotThrow(() -> limiter.acquire(UUID.randomUUID()));

        clock.advance(Duration.ofHours(1));
        assertDoesNotThrow(() -> limiter.acquire(shop));
    }
}
