package ru.marketplace.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
class NotiSendEmailService implements EmailService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Сколько символов ответа NotiSend пишем в лог: для диагностики хватает, простыню HTML не тащим */
    static final int MAX_LOGGED_BODY = 500;

    private final WebClient webClient;
    private final String apiKey;
    private final String emailAddress;
    private final String fromName;

    NotiSendEmailService(@Qualifier("notisendWebClient") WebClient webClient,
                         @Value("${email.notisend.api.key}") String apiKey,
                         @Value("${email.notisend.email.address}") String emailAddress,
                         @Value("${email.notisend.from.name}") String fromName) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.emailAddress = emailAddress;
        this.fromName = fromName;
    }

    @Override
    public void sendEmail(String to, String subject, String html) {
        // Поля NotiSend — snake_case. Map, а не record: без Gson record-поля с null ушли бы явными null
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("from_email", emailAddress);
        request.put("from_name", fromName);
        request.put("to", to);
        request.put("subject", subject);
        request.put("html", html);

        SendResult result;
        try {
            result = webClient.post()
                    .uri("/email/messages")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new SendResult(response.statusCode().value(), body)))
                    .timeout(TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            // Адрес — только маской: полная почта покупателя/мерчанта в логе — лишние персональные данные,
            // а контекст (заказ, вход) вызывающий и так пишет сам
            log.error("NotiSend недоступен при отправке письма на {}: {}", EmailMasking.mask(to), e.getMessage());
            throw new RuntimeException("NotiSend недоступен: " + e.getMessage(), e);
        }

        if (result == null) {
            throw new RuntimeException("NotiSend: пустой ответ");
        }
        if (result.status() < 200 || result.status() >= 300) {
            // Ошибка валидации NotiSend может процитировать адрес получателя — в лог и в исключение
            // (его вызывающий тоже логирует) идёт тело с маской вместо адреса и обрезанное
            String body = sanitizeBody(result.body(), to);
            log.error("NotiSend отклонил письмо на {}: HTTP {} body: {}", EmailMasking.mask(to), result.status(), body);
            throw new RuntimeException("NotiSend вернул HTTP " + result.status() + ": " + body);
        }
        log.debug("NotiSend принял письмо на {}: HTTP {}", EmailMasking.mask(to), result.status());
    }

    /** Тело ответа NotiSend для лога: адрес получателя заменён маской, длина ограничена. */
    static String sanitizeBody(String body, String to) {
        if (body == null) {
            return "";
        }
        String masked = to == null || to.isBlank() ? body : body.replace(to, EmailMasking.mask(to));
        return masked.length() > MAX_LOGGED_BODY ? masked.substring(0, MAX_LOGGED_BODY) + "…" : masked;
    }

    private record SendResult(int status, String body) {}
}
