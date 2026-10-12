package ru.marketplace.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.marketplace.payment.dto.tinkoff.TinkoffGetStateResponse;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitRequest;
import ru.marketplace.payment.dto.tinkoff.TinkoffInitResponse;
import ru.marketplace.payment.dto.tinkoff.TinkoffReceipt;
import ru.marketplace.payment.dto.tinkoff.TinkoffReceiptItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Клиент Т-Банка на заглушке HTTP: что и как подписываем, что уходит в теле и как разбираем отказы. */
class TinkoffClientTest {

    private static final TinkoffCredentials CREDENTIALS =
            new TinkoffCredentials(TinkoffTokenServiceTest.DOC_TERMINAL, TinkoffTokenServiceTest.DOC_PASSWORD);
    /** sha256(Password + "13660" + TerminalKey) — подпись GetState (ключи по алфавиту: Password < PaymentId). */
    private static final String GET_STATE_TOKEN = "8880ff866c66bcc1fc890a76438ca4e0def99c743e681e08bfb6b5c97c8e0c51";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private TinkoffClient client(HttpStatus status, String responseJson) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest captured = new MockClientHttpRequest(request.method(), request.url());
            request.body().insert(captured, new BodyInserter.Context() {
                @Override
                public List<HttpMessageWriter<?>> messageWriters() {
                    return ExchangeStrategies.withDefaults().messageWriters();
                }

                @Override
                public Optional<ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }

                @Override
                public Map<String, Object> hints() {
                    return Map.of();
                }
            }).block();
            paths.add(request.url().getPath());
            bodies.add(captured.getBodyAsString().block());
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(responseJson)
                    .build());
        };
        WebClient webClient = WebClient.builder()
                .baseUrl("https://securepay.example/v2")
                .exchangeFunction(exchange)
                .build();
        return new TinkoffClient(webClient, new TinkoffTokenService());
    }

    private static TinkoffInitRequest docInitRequest() {
        return TinkoffInitRequest.builder()
                .amount(19200L)
                .orderId("00000")
                .description("Подарочная карта на 1000 рублей")
                .build();
    }

    @Test
    void initSignsRootParamsLikeDocumentationAndSendsReceiptUnsigned() throws Exception {
        TinkoffClient client = client(HttpStatus.OK,
                "{\"Success\":true,\"Status\":\"NEW\",\"PaymentId\":\"13660\",\"OrderId\":\"00000\","
                        + "\"Amount\":19200,\"PaymentURL\":\"https://securepay.example/new/abc\"}");
        TinkoffInitRequest request = docInitRequest();
        request.setReceipt(TinkoffReceipt.builder()
                .email("buyer@example.com")
                .taxation("usn_income")
                .items(List.of(TinkoffReceiptItem.builder().name("Свеча").price(19200L).quantity(1).amount(19200L)
                        .tax("none").paymentMethod("full_payment").paymentObject("commodity").build()))
                .build());

        TinkoffInitResponse response = client.init(request, CREDENTIALS);

        assertEquals("13660", response.getPaymentId());
        assertEquals("https://securepay.example/new/abc", response.getPaymentURL());
        assertEquals(List.of("/v2/Init"), paths);

        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertEquals("MerchantTerminalKey", body.get("TerminalKey").asText());
        assertEquals(TinkoffTokenServiceTest.DOC_INIT_TOKEN, body.get("Token").asText());
        assertEquals(19200, body.get("Amount").asLong());
        assertEquals("Свеча", body.get("Receipt").get("Items").get(0).get("Name").asText());
        assertEquals("usn_income", body.get("Receipt").get("Taxation").asText());
        // null-поля не уходят в банк (иначе они попали бы в чужую подпись)
        assertFalse(body.has("SuccessURL"));
        assertFalse(body.toString().contains("Password"));
    }

    @Test
    void initSignsAllScalarParams() {
        TinkoffClient client = client(HttpStatus.OK,
                "{\"Success\":true,\"PaymentId\":\"1\",\"PaymentURL\":\"https://pay\"}");
        TinkoffInitRequest request = docInitRequest();
        request.setPayType("O");
        request.setSuccessURL("https://shop.example/s?order=00000&status=success");
        request.setFailURL("https://shop.example/s?order=00000&status=fail");
        request.setNotificationURL("https://api.example/api/public/payment/tinkoff-webhook");
        request.setRedirectDueDate("2026-10-01T12:15:00+03:00");

        client.init(request, CREDENTIALS);

        Map<String, String> expected = Map.of(
                "TerminalKey", "MerchantTerminalKey",
                "Amount", "19200",
                "OrderId", "00000",
                "Description", "Подарочная карта на 1000 рублей",
                "PayType", "O",
                "SuccessURL", "https://shop.example/s?order=00000&status=success",
                "FailURL", "https://shop.example/s?order=00000&status=fail",
                "NotificationURL", "https://api.example/api/public/payment/tinkoff-webhook",
                "RedirectDueDate", "2026-10-01T12:15:00+03:00");
        assertEquals(expected, TinkoffClient.rootParams(request));
        assertEquals(new TinkoffTokenService().sign(expected, TinkoffTokenServiceTest.DOC_PASSWORD), request.getToken());
    }

    @Test
    void initRejectedByBankThrowsWithBankMessage() {
        TinkoffClient client = client(HttpStatus.OK,
                "{\"Success\":false,\"ErrorCode\":\"204\",\"Message\":\"Неверный токен.\","
                        + "\"Details\":\"Проверьте пароль терминала.\"}");

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.init(docInitRequest(), CREDENTIALS));

        assertEquals("Неверный токен. (Проверьте пароль терминала.)", e.getMessage());
    }

    @Test
    void initWithoutMessageReportsErrorCode() {
        TinkoffClient client = client(HttpStatus.OK, "{\"Success\":false,\"ErrorCode\":\"9999\"}");

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.init(docInitRequest(), CREDENTIALS));

        assertEquals("код ошибки 9999", e.getMessage());
    }

    @Test
    void initWithoutPaymentUrlIsFailure() {
        TinkoffClient client = client(HttpStatus.OK, "{\"Success\":true,\"PaymentId\":\"1\"}");

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.init(docInitRequest(), CREDENTIALS));

        assertEquals("Т-Банк не вернул ссылку на оплату", e.getMessage());
    }

    @Test
    void httpErrorBecomesTinkoffException() {
        TinkoffClient client = client(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.init(docInitRequest(), CREDENTIALS));

        assertEquals("Т-Банк ответил ошибкой 500", e.getMessage());
    }

    @Test
    void transportErrorBecomesTinkoffException() {
        WebClient broken = WebClient.builder()
                .baseUrl("https://securepay.example/v2")
                .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
                .build();
        TinkoffClient client = new TinkoffClient(broken, new TinkoffTokenService());

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.getState("13660", CREDENTIALS));

        assertEquals("Т-Банк не отвечает", e.getMessage());
    }

    @Test
    void getStateSignsTerminalAndPaymentId() throws Exception {
        TinkoffClient client = client(HttpStatus.OK,
                "{\"Success\":true,\"Status\":\"CONFIRMED\",\"PaymentId\":\"13660\",\"Amount\":19200}");

        TinkoffGetStateResponse response = client.getState("13660", CREDENTIALS);

        assertEquals("CONFIRMED", response.getStatus());
        assertEquals(List.of("/v2/GetState"), paths);
        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertEquals("MerchantTerminalKey", body.get("TerminalKey").asText());
        assertEquals("13660", body.get("PaymentId").asText());
        assertEquals(GET_STATE_TOKEN, body.get("Token").asText());
    }

    @Test
    void getStateRejectedThrows() {
        TinkoffClient client = client(HttpStatus.OK, "{\"Success\":false,\"Message\":\"Платёж не найден\"}");

        TinkoffClient.TinkoffException e = assertThrows(TinkoffClient.TinkoffException.class,
                () -> client.getState("13660", CREDENTIALS));

        assertTrue(e.getMessage().contains("Платёж не найден"));
    }

    @Test
    void credentialsNeverPrintPassword() {
        assertFalse(CREDENTIALS.toString().contains(TinkoffTokenServiceTest.DOC_PASSWORD));
    }
}
