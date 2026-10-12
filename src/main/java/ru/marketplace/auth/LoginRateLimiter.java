package ru.marketplace.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.marketplace.common.FixedWindowRateLimiter;

import java.time.Clock;
import java.time.Duration;

/**
 * Лимиты ручек входа с одного IP (IP — {@link ru.marketplace.common.ClientIp}).
 * <ul>
 *   <li>Запрос кода: в anyforms код получали только заведённые почты, здесь — любая (регистрация = первый вход),
 *   и без лимита ручку можно было бы использовать как бесплатную рассылку писем от нашего имени на чужие адреса.</li>
 *   <li>Проверка кода: без лимита одним адресом можно было сжигать коды чужой почты (5 неверных попыток сразу
 *   после того, как владелец запросил код) и перебирать их без остановки. Общий потолок неверных кодов
 *   на почту держит AuthServiceImpl, а этот лимит не даёт одному адресу выжечь его за всех.</li>
 * </ul>
 * Окно фиксированное: счётчик живёт час с первого запроса, потом обнуляется.
 */
@Component
public class LoginRateLimiter {

    static final String LIMIT_MESSAGE = "Слишком много запросов кода. Попробуйте позже";
    static final String VERIFY_LIMIT_MESSAGE = "Слишком много попыток входа. Попробуйте позже";

    private static final Duration WINDOW = Duration.ofHours(1);

    private final FixedWindowRateLimiter requestCodeLimiter;
    private final FixedWindowRateLimiter verifyCodeLimiter;

    public LoginRateLimiter(@Value("${auth.login-code.ip-limit-per-hour}") int limitPerHour,
                            @Value("${auth.login-code.verify-ip-limit-per-hour}") int verifyLimitPerHour,
                            Clock clock) {
        if (limitPerHour <= 0) {
            throw new IllegalStateException("auth.login-code.ip-limit-per-hour должен быть больше нуля");
        }
        if (verifyLimitPerHour <= 0) {
            throw new IllegalStateException("auth.login-code.verify-ip-limit-per-hour должен быть больше нуля");
        }
        this.requestCodeLimiter = new FixedWindowRateLimiter(limitPerHour, WINDOW, LIMIT_MESSAGE, clock);
        this.verifyCodeLimiter = new FixedWindowRateLimiter(verifyLimitPerHour, WINDOW, VERIFY_LIMIT_MESSAGE, clock);
    }

    /** Засчитывает запрос кода с этого IP; сверх лимита за окно — 429. */
    public void acquire(String ip) {
        requestCodeLimiter.acquire(ip);
    }

    /** Засчитывает проверку кода с этого IP; сверх лимита за окно — 429. */
    public void acquireVerify(String ip) {
        verifyCodeLimiter.acquire(ip);
    }
}
