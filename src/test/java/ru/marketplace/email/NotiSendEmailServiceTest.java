package ru.marketplace.email;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/** Ошибки NotiSend не тащат полный адрес получателя ни в лог, ни в текст исключения (его логирует вызывающий). */
class NotiSendEmailServiceTest {

    private static final String TO = "anna.petrova@example.com";

    private static NotiSendEmailService service(WebClient webClient) {
        return new NotiSendEmailService(webClient, "key", "noreply@market.example", "marketplace");
    }

    private static WebClient answering(HttpStatus status, String body) {
        return WebClient.builder()
                .baseUrl("https://api.notisend.test/v1")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status).body(body).build()))
                .build();
    }

    @Test
    void acceptedEmailDoesNotThrow() {
        assertDoesNotThrow(() -> service(answering(HttpStatus.OK, "{\"id\":1}")).sendEmail(TO, "Тема", "<p>hi</p>"));
    }

    @Test
    void rejectedEmailErrorHasMaskedAddressOnly() {
        String echoed = "{\"errors\":[{\"detail\":\"Получатель " + TO + " в стоп-листе\"}]}";

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service(answering(HttpStatus.UNPROCESSABLE_ENTITY, echoed)).sendEmail(TO, "Тема", "<p>hi</p>"));

        assertTrue(e.getMessage().contains("HTTP 422"));
        assertFalse(e.getMessage().contains(TO), e.getMessage());
        assertTrue(e.getMessage().contains("a***@example.com"), e.getMessage());
    }

    @Test
    void transportErrorDoesNotMentionAddress() {
        WebClient down = WebClient.builder()
                .baseUrl("https://api.notisend.test/v1")
                .exchangeFunction(request -> Mono.error(new IllegalStateException("api.notisend.test: nodename nor servname provided")))
                .build();

        RuntimeException e = assertThrows(RuntimeException.class, () -> service(down).sendEmail(TO, "Тема", "<p>hi</p>"));

        assertTrue(e.getMessage().startsWith("NotiSend недоступен"));
        assertFalse(e.getMessage().contains(TO));
    }

    @Test
    void sanitizedBodyIsMaskedAndTruncated() {
        assertEquals("bad a***@example.com", NotiSendEmailService.sanitizeBody("bad " + TO, TO));
        String longBody = "x".repeat(2_000);
        String sanitized = NotiSendEmailService.sanitizeBody(longBody, TO);
        assertEquals(NotiSendEmailService.MAX_LOGGED_BODY + 1, sanitized.length());
        assertTrue(sanitized.endsWith("…"));
        assertEquals("", NotiSendEmailService.sanitizeBody(null, TO));
    }
}
