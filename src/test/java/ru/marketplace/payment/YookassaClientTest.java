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
import ru.marketplace.payment.dto.yookassa.YookassaAmount;
import ru.marketplace.payment.dto.yookassa.YookassaCreatePaymentRequest;
import ru.marketplace.payment.dto.yookassa.YookassaPayment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Клиент ЮKassa на заглушке HTTP: авторизация, Idempotence-Key, тело платежа и разбор отказов. */
class YookassaClientTest {

    private static final YookassaCredentials CREDENTIALS = new YookassaCredentials("123456", "live_key");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> paths = new ArrayList<>();
    private final List<HttpHeaders> headers = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private YookassaClient client(HttpStatus status, String responseJson) {
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
            headers.add(request.headers());
            bodies.add(captured.getBodyAsString().defaultIfEmpty("").block());
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(responseJson)
                    .build());
        };
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.yookassa.example/v3")
                .exchangeFunction(exchange)
                .build();
        return new YookassaClient(webClient, objectMapper);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createPaymentSendsBasicAuthIdempotenceKeyAndSnakeCaseBody() throws Exception {
        YookassaClient client = client(HttpStatus.OK, """
                {"id":"2d1b-yk","status":"pending","amount":{"value":"192.00","currency":"RUB"},
                 "confirmation":{"type":"redirect","confirmation_url":"https://yoomoney.ru/checkout/abc"},"paid":false}""");
        YookassaCreatePaymentRequest request = YookassaCreatePaymentRequest.builder()
                .amount(YookassaAmount.rub(19200))
                .capture(true)
                .confirmation(new YookassaCreatePaymentRequest.Confirmation("redirect", "https://market.example/s?order=A1B2C3"))
                .metadata(Map.of("order_id", "A1B2C3"))
                .build();

        YookassaPayment payment = client.createPayment(request, "idem-1", CREDENTIALS);

        assertEquals("2d1b-yk", payment.getId());
        assertEquals("https://yoomoney.ru/checkout/abc", payment.getConfirmation().getConfirmationUrl());
        assertEquals("/v3/payments", paths.get(0));
        assertEquals(basic("123456", "live_key"), headers.get(0).getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals("idem-1", headers.get(0).getFirst("Idempotence-Key"));
        JsonNode body = objectMapper.readTree(bodies.get(0));
        assertEquals("192.00", body.at("/amount/value").asText());
        assertEquals("https://market.example/s?order=A1B2C3", body.at("/confirmation/return_url").asText());
        assertEquals("A1B2C3", body.at("/metadata/order_id").asText());
        assertFalse(body.has("receipt"), "без чека поля receipt нет совсем");
    }

    @Test
    void rejectionCarriesYookassaDescription() {
        YookassaClient client = client(HttpStatus.BAD_REQUEST, """
                {"type":"error","code":"invalid_request","description":"Receipt is missing or illegal","parameter":"receipt"}""");

        YookassaClient.YookassaException e = assertThrows(YookassaClient.YookassaException.class,
                () -> client.createPayment(new YookassaCreatePaymentRequest(), "idem", CREDENTIALS));
        assertEquals("Receipt is missing or illegal", e.getMessage());
    }

    @Test
    void wrongKeysOnCreateAreExplained() {
        YookassaClient client = client(HttpStatus.UNAUTHORIZED, "{\"type\":\"error\",\"code\":\"invalid_credentials\"}");

        YookassaClient.YookassaException e = assertThrows(YookassaClient.YookassaException.class,
                () -> client.createPayment(new YookassaCreatePaymentRequest(), "idem", CREDENTIALS));
        assertTrue(e.getMessage().contains("ключи"));
    }

    @Test
    void getPaymentReadsStatusAndRefund() {
        YookassaClient client = client(HttpStatus.OK, """
                {"id":"2d1b-yk","status":"succeeded","amount":{"value":"192.00","currency":"RUB"},
                 "refunded_amount":{"value":"192.00","currency":"RUB"},"metadata":{"order_id":"A1B2C3"}}""");

        YookassaPayment payment = client.getPayment("2d1b-yk", CREDENTIALS);

        assertEquals("/v3/payments/2d1b-yk", paths.get(0));
        assertEquals(19200L, payment.getAmount().toKopecks());
        assertEquals("A1B2C3", payment.getMetadata().get("order_id"));
    }

    @Test
    void checkDistinguishesWrongKeysFromOutage() {
        assertEquals(YookassaClient.Check.OK, client(HttpStatus.OK, "{\"account_id\":\"123456\"}").check(CREDENTIALS));
        assertEquals("/v3/me", paths.get(0));
        assertEquals(YookassaClient.Check.REJECTED, client(HttpStatus.UNAUTHORIZED, "{}").check(CREDENTIALS));
        assertEquals(YookassaClient.Check.REJECTED, client(HttpStatus.FORBIDDEN, "{}").check(CREDENTIALS));
        assertEquals(YookassaClient.Check.UNAVAILABLE, client(HttpStatus.SERVICE_UNAVAILABLE, "{}").check(CREDENTIALS));
    }

    @Test
    void credentialsNeverPrintSecret() {
        assertFalse(CREDENTIALS.toString().contains("live_key"));
    }
}
