package ru.marketplace.common;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Счётчик запросов по ключу (IP, магазин) с фиксированным окном в памяти процесса — Guava cache,
 * как лимит кода входа в anyforms. Окно отсчитывается от первого запроса ключа и по истечении
 * обнуляется целиком. Сверх лимита — 429 с текстом для пользователя (GlobalExceptionHandler отдаст {error}).
 * <p>
 * Лимит живёт в одном инстансе и сбрасывается рестартом: это защита от массового злоупотребления
 * публичными ручками, а не точный учёт. Приложение крутится одним инстансом, так что этого достаточно.
 */
public class FixedWindowRateLimiter {

    /**
     * Сколько ключей держим в памяти: без потолка перебор ключей раздул бы кэш.
     * При вытеснении счётчик ключа просто начнётся заново.
     */
    static final long MAX_TRACKED_KEYS = 100_000;
    private static final String UNKNOWN_KEY = "unknown";

    private final int limit;
    private final String message;
    private final Cache<String, AtomicInteger> counters;

    public FixedWindowRateLimiter(int limit, Duration window, String message, Clock clock) {
        if (limit <= 0) {
            throw new IllegalStateException("Лимит запросов должен быть больше нуля");
        }
        this.limit = limit;
        this.message = message;
        // Время кэша — от общего Clock приложения: в тестах окно сдвигается без sleep
        this.counters = CacheBuilder.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(MAX_TRACKED_KEYS)
                .ticker(clockTicker(clock))
                .build();
    }

    /** Засчитывает запрос по ключу; сверх лимита за окно — 429. Пустой ключ — один общий счётчик. */
    public void acquire(String key) {
        String k = key == null || key.isBlank() ? UNKNOWN_KEY : key;
        AtomicInteger counter;
        try {
            // Запись в кэш только при первом запросе — от неё и отсчитывается окно
            counter = counters.get(k, AtomicInteger::new);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
        if (counter.incrementAndGet() > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    private static Ticker clockTicker(Clock clock) {
        return new Ticker() {
            @Override
            public long read() {
                Instant now = clock.instant();
                return TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
            }
        };
    }
}
