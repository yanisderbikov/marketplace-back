package ru.marketplace.storage;

import org.springframework.stereotype.Component;
import ru.marketplace.common.FixedWindowRateLimiter;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Лимит подписей на загрузку (presign) на магазин. Регистрация самообслуживанием: любой, кто получил код
 * на почту, заводит магазин и без лимита мог бы подписывать загрузки в цикле — хранение и трафик бакета
 * оплачивает платформа. 300 в час — с запасом на заливку каталога (20 фото × 15 товаров), но не склад файлов.
 * Константа, а не env: это защита от злоупотребления, а не настройка магазина.
 */
@Component
public class UploadRateLimiter {

    static final int PRESIGNS_PER_HOUR = 300;
    static final String LIMIT_MESSAGE = "Слишком много загрузок за час. Попробуйте позже";

    private final FixedWindowRateLimiter limiter;

    public UploadRateLimiter(Clock clock) {
        this.limiter = new FixedWindowRateLimiter(PRESIGNS_PER_HOUR, Duration.ofHours(1), LIMIT_MESSAGE, clock);
    }

    /** Засчитывает подпись загрузки магазину; сверх лимита за час — 429. */
    public void acquire(UUID shopId) {
        limiter.acquire(String.valueOf(shopId));
    }
}
