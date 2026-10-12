package ru.marketplace.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.payment.dto.yookassa.YookassaCreatePaymentRequest;
import ru.marketplace.payment.dto.yookassa.YookassaError;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;

import java.time.Duration;
import java.util.Locale;

/**
 * HTTP-клиент API ЮKassa v3: создать платёж, узнать его статус, проверить ключи магазина.
 * Ключи — аргументом (у каждого магазина свои), HTTP Basic shopId:секретный ключ.
 * Любая неудача — {@link YookassaException} с короткой причиной по-русски, как у {@link TinkoffClient}.
 */
@Service
@Slf4j
public class YookassaClient {

    /** Столько же, сколько ждём Т-Банк: дальше честнее ответить покупателю 502. */
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Проверку ключей в кабинете ждём меньше — мерчант смотрит на кнопку «Подключить». */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    /** Лимит ЮKassa на description платежа. */
    public static final int DESCRIPTION_MAX_LENGTH = 128;

    public enum Check { OK, REJECTED, UNAVAILABLE }

    private final WebClient yookassaWebClient;
    private final ObjectMapper objectMapper;

    public YookassaClient(@Qualifier("yookassaWebClient") WebClient yookassaWebClient, ObjectMapper objectMapper) {
        this.yookassaWebClient = yookassaWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Создать платёж. idempotenceKey — один и тот же для повторов одного заказа: ЮKassa вернёт
     * уже созданный платёж, а не второй.
     */
    public YookassaPayment createPayment(YookassaCreatePaymentRequest request, String idempotenceKey,
                                         YookassaCredentials credentials) {
        YookassaPayment payment;
        try {
            payment = yookassaWebClient.post()
                    .uri("/payments")
                    .headers(h -> {
                        h.setBasicAuth(credentials.shopId(), credentials.secretKey());
                        h.set("Idempotence-Key", idempotenceKey);
                    })
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(YookassaPayment.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("ЮKassa отклонила создание платежа (магазин {}): {} - {}",
                    credentials.shopId(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new YookassaException(rejectionReason(e));
        } catch (RuntimeException e) {
            log.error("ЮKassa недоступна при создании платежа: {}", e.toString());
            throw new YookassaException("ЮKassa не отвечает");
        }
        if (payment == null || isBlank(payment.getId()) || payment.getConfirmation() == null
                || isBlank(payment.getConfirmation().getConfirmationUrl())) {
            log.error("ЮKassa не вернула id/confirmation_url платежа: {}", payment);
            throw new YookassaException("ЮKassa не вернула ссылку на оплату");
        }
        log.info("Создан платёж ЮKassa {} (магазин {})", payment.getId(), credentials.shopId());
        return payment;
    }

    /** Платёж по id — его текущий статус, сумма и metadata. */
    public YookassaPayment getPayment(String paymentId, YookassaCredentials credentials) {
        YookassaPayment payment;
        try {
            payment = yookassaWebClient.get()
                    .uri("/payments/{id}", paymentId)
                    .headers(h -> h.setBasicAuth(credentials.shopId(), credentials.secretKey()))
                    .retrieve()
                    .bodyToMono(YookassaPayment.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            throw new YookassaException("ЮKassa отклонила запрос платежа " + paymentId + ": " + rejectionReason(e));
        } catch (RuntimeException e) {
            throw new YookassaException("ЮKassa не отвечает");
        }
        if (payment == null) {
            throw new YookassaException("пустой ответ ЮKassa");
        }
        return payment;
    }

    /**
     * Проверка ключей перед сохранением: GET /v3/me отвечает 200 только на верную пару shopId + ключ.
     * 401/403 — ключи не приняты, остальное (таймаут, 5xx) — ЮKassa сейчас не ответила.
     */
    public Check check(YookassaCredentials credentials) {
        try {
            yookassaWebClient.get()
                    .uri("/me")
                    .headers(h -> h.setBasicAuth(credentials.shopId(), credentials.secretKey()))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(CHECK_TIMEOUT)
                    .block();
            return Check.OK;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                    || e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                return Check.REJECTED;
            }
            log.warn("ЮKassa ответила {} на проверку ключей магазина {}", e.getStatusCode(), credentials.shopId());
            return Check.UNAVAILABLE;
        } catch (RuntimeException e) {
            log.warn("ЮKassa недоступна при проверке ключей магазина {}: {}", credentials.shopId(), e.toString());
            return Check.UNAVAILABLE;
        }
    }

    /**
     * Платёж ЮKassa → статус оплаты заказа; null — промежуточный (pending, waiting_for_capture)
     * или неизвестный: заказ не трогаем. Возврат в ЮKassa — отдельный объект, сам платёж остаётся
     * succeeded, но с refunded_amount: любой возврат, как и у Т-Банка, снимает оплату (CANCELED).
     */
    public static PaymentStatus toPaymentStatus(YookassaPayment payment) {
        if (payment == null || payment.getStatus() == null) {
            return null;
        }
        return switch (payment.getStatus().trim().toLowerCase(Locale.ROOT)) {
            case "succeeded" -> refunded(payment) ? PaymentStatus.CANCELED : PaymentStatus.PAID;
            // capture=true: canceled — неуспешная оплата или истёкший платёж, деньги не списывались
            case "canceled" -> PaymentStatus.FAILED;
            default -> null;
        };
    }

    private static boolean refunded(YookassaPayment payment) {
        if (payment.getRefundedAmount() == null) {
            return false;
        }
        Long kopecks = payment.getRefundedAmount().toKopecks();
        return kopecks != null && kopecks > 0;
    }

    /** Текст отказа для людей: description из тела ошибки ЮKassa, иначе HTTP-код. */
    private String rejectionReason(WebClientResponseException e) {
        if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return "ЮKassa не приняла ключи магазина";
        }
        try {
            YookassaError error = objectMapper.readValue(e.getResponseBodyAsString(), YookassaError.class);
            if (error != null && !isBlank(error.getDescription())) {
                return error.getDescription();
            }
        } catch (Exception ignored) {
            // Тело не JSON — обойдёмся кодом
        }
        return "ЮKassa ответила ошибкой " + e.getStatusCode().value();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Отказ или недоступность ЮKassa; getMessage() — короткая причина по-русски. */
    public static class YookassaException extends RuntimeException {
        public YookassaException(String reason) {
            super(reason);
        }
    }
}
