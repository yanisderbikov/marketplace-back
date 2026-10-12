package ru.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Часы, которые тест двигает сам: сроки кода и окно лимита проверяются без sleep. */
public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
