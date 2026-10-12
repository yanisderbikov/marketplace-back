package ru.marketplace.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.marketplace.payment.dto.tinkoff.TinkoffGetStateRequest;
import ru.marketplace.payment.dto.tinkoff.TinkoffGetStateResponse;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitRequest;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP-клиент Т-Банка (перенос anyforms TinkoffServiceImpl: только Init и GetState).
 * Терминал не из env, а аргументом — у каждого магазина свой, деньги идут мерчанту напрямую.
 * Любая неудача (HTTP-ошибка, таймаут, Success=false) — {@link TinkoffException} с коротким
 * текстом причины: чекаут показывает его покупателю после «Не удалось создать платёж: ».
 */
@Service
@Slf4j
public class TinkoffClient {

    /**
     * Сколько ждём ответа банка. В anyforms таймаута не было — зависший Init держал поток
     * запроса покупателя бесконечно; 20 секунд хватает с запасом, дальше честнее ответить 502.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient tinkoffWebClient;
    private final TinkoffTokenService tinkoffTokenService;

    public TinkoffClient(@Qualifier("tinkoffWebClient") WebClient tinkoffWebClient,
                         TinkoffTokenService tinkoffTokenService) {
        this.tinkoffWebClient = tinkoffWebClient;
        this.tinkoffTokenService = tinkoffTokenService;
    }

    /** Создать платёж. Проставляет в request TerminalKey и Token; возвращает ответ только с PaymentId и PaymentURL. */
    public TinkoffInitResponse init(TinkoffInitRequest request, TinkoffCredentials credentials) {
        request.setTerminalKey(credentials.terminalKey());
        request.setToken(tinkoffTokenService.sign(rootParams(request), credentials.password()));

        TinkoffInitResponse response = post("/Init", request, TinkoffInitResponse.class);

        if (!Boolean.TRUE.equals(response.getSuccess())) {
            log.error("Т-Банк отклонил Init заказа {} (терминал {}): код {}, {} / {}",
                    request.getOrderId(), credentials.terminalKey(),
                    response.getErrorCode(), response.getMessage(), response.getDetails());
            throw new TinkoffException(rejectionReason(response.getMessage(), response.getDetails(),
                    response.getErrorCode()));
        }
        if (isBlank(response.getPaymentId()) || isBlank(response.getPaymentURL())) {
            log.error("Т-Банк не вернул PaymentId/PaymentURL для заказа {}: {}", request.getOrderId(), response);
            throw new TinkoffException("Т-Банк не вернул ссылку на оплату");
        }

        log.info("Создан платёж Т-Банка {} для заказа {}", response.getPaymentId(), request.getOrderId());
        return response;
    }

    /** Текущий статус платежа у банка. */
    public TinkoffGetStateResponse getState(String paymentId, TinkoffCredentials credentials) {
        TinkoffGetStateRequest request = TinkoffGetStateRequest.builder()
                .terminalKey(credentials.terminalKey())
                .paymentId(paymentId)
                .build();
        request.setToken(tinkoffTokenService.sign(Map.of(
                "TerminalKey", credentials.terminalKey(),
                "PaymentId", paymentId), credentials.password()));

        TinkoffGetStateResponse response = post("/GetState", request, TinkoffGetStateResponse.class);

        if (!Boolean.TRUE.equals(response.getSuccess())) {
            throw new TinkoffException("Т-Банк отклонил GetState для платежа " + paymentId + ": "
                    + rejectionReason(response.getMessage(), response.getDetails(), response.getErrorCode()));
        }
        return response;
    }

    private <T> T post(String uri, Object body, Class<T> responseType) {
        T response;
        try {
            response = tinkoffWebClient
                    .post()
                    .uri(uri)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(responseType)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Ошибка Т-Банка на {}: {} - {}", uri, e.getStatusCode(), e.getResponseBodyAsString());
            throw new TinkoffException("Т-Банк ответил ошибкой " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            // Таймаут, обрыв соединения, DNS — для покупателя одно и то же
            log.error("Т-Банк недоступен на {}: {}", uri, e.toString());
            throw new TinkoffException("Т-Банк не отвечает");
        }
        if (response == null) {
            throw new TinkoffException("пустой ответ Т-Банка");
        }
        return response;
    }

    /** Корневые скалярные параметры Init для подписи; Receipt (объект) в подпись не входит. */
    static Map<String, String> rootParams(TinkoffInitRequest request) {
        Map<String, String> params = new HashMap<>();
        params.put("TerminalKey", request.getTerminalKey());
        params.put("Amount", String.valueOf(request.getAmount()));
        params.put("OrderId", request.getOrderId());
        putIfPresent(params, "Description", request.getDescription());
        putIfPresent(params, "PayType", request.getPayType());
        putIfPresent(params, "SuccessURL", request.getSuccessURL());
        putIfPresent(params, "FailURL", request.getFailURL());
        putIfPresent(params, "NotificationURL", request.getNotificationURL());
        putIfPresent(params, "RedirectDueDate", request.getRedirectDueDate());
        return params;
    }

    private static void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    /** Текст отказа банка для людей: Message (+ Details), иначе код ошибки. */
    private static String rejectionReason(String message, String details, String errorCode) {
        if (!isBlank(message)) {
            return isBlank(details) || details.equals(message) ? message : message + " (" + details + ")";
        }
        if (!isBlank(details)) {
            return details;
        }
        return "код ошибки " + (isBlank(errorCode) ? "неизвестен" : errorCode);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Отказ или недоступность Т-Банка; getMessage() — короткая причина по-русски. */
    public static class TinkoffException extends RuntimeException {
        public TinkoffException(String reason) {
            super(reason);
        }
    }
}
