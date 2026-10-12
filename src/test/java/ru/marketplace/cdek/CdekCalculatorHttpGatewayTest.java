package ru.marketplace.cdek;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import ru.marketplace.cdek.dto.CdekLocation;
import ru.marketplace.cdek.dto.CdekPackage;
import ru.marketplace.cdek.dto.CdekTariffQuote;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdekCalculatorHttpGatewayTest {

    private static final CdekLocation FROM = new CdekLocation("RU", "198095", "Санкт-Петербург", "ул. Трефолева, 9");
    private static final CdekLocation TO = new CdekLocation("RU", "101000", "Москва", " ");
    private static final List<CdekPackage> PACKAGES = List.of(new CdekPackage(300, 15, 15, 15), new CdekPackage(1200, 28, 26, 10));

    private static final CdekCredentials KEYS = new CdekCredentials("client", "secret");

    private final CdekAuthService auth = mock(CdekAuthService.class);
    private final CdekWebClientStub stub = new CdekWebClientStub();
    private final CdekCalculatorHttpGateway gateway = new CdekCalculatorHttpGateway(auth, stub.webClient);

    @Test
    void buildsTariffRequestLikeAnyforms() {
        String json = CdekCalculatorHttpGateway.buildRequest(136, FROM, TO, PACKAGES).toString();

        assertEquals("{\"type\":1,\"currency\":1,\"tariff_code\":136,"
                + "\"from_location\":{\"country_code\":\"RU\",\"postal_code\":\"198095\",\"city\":\"Санкт-Петербург\",\"address\":\"ул. Трефолева, 9\"},"
                + "\"to_location\":{\"country_code\":\"RU\",\"postal_code\":\"101000\",\"city\":\"Москва\"},"
                + "\"packages\":[{\"weight\":300,\"length\":15,\"width\":15,\"height\":15},"
                + "{\"weight\":1200,\"length\":28,\"width\":26,\"height\":10}]}", json);
    }

    @Test
    void postsWithBearerTokenAndParsesQuote() {
        when(auth.getAccessToken(KEYS)).thenReturn("tok");
        stub.respond(HttpStatus.OK, "{\"delivery_sum\":450.0,\"period_min\":2,\"period_max\":4,\"total_sum\":470.5}");

        CdekTariffQuote quote = gateway.calculate(KEYS, 136, FROM, TO, PACKAGES);

        assertEquals(new BigDecimal("450.00"), quote.deliverySum());
        assertEquals(2, quote.periodMin());
        assertEquals(4, quote.periodMax());
        CdekWebClientStub.Recorded request = stub.lastRequest();
        assertEquals("https://api.cdek.test/v2/calculator/tariff", request.request().url().toString());
        assertEquals("Bearer tok", request.request().headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(request.writtenHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        assertEquals(CdekCalculatorHttpGateway.buildRequest(136, FROM, TO, PACKAGES).toString(), request.body());
    }

    @Test
    void parsesSumAsTwoDecimalRublesAndMissingPeriods() {
        CdekTariffQuote quote = CdekCalculatorHttpGateway.parse("{\"delivery_sum\":\"1234.5\",\"period_min\":null}");

        assertEquals(new BigDecimal("1234.50"), quote.deliverySum());
        assertNull(quote.periodMin());
        assertNull(quote.periodMax());
    }

    @Test
    void cdekErrorsBecomeCalculationException() {
        CdekCalculationException inBody = assertThrows(CdekCalculationException.class, () -> CdekCalculatorHttpGateway.parse(
                "{\"errors\":[{\"code\":\"v2_entity_empty\",\"message\":\"Невозможно осуществить доставку по этому направлению\"}]}"));
        assertEquals("Невозможно осуществить доставку по этому направлению", inBody.getMessage());

        assertEquals("Код ошибки СДЭК: ERR_1", assertThrows(CdekCalculationException.class,
                () -> CdekCalculatorHttpGateway.parse("{\"errors\":[{\"code\":\"ERR_1\"}]}")).getMessage());
        assertEquals("В ответе СДЭК нет стоимости доставки", assertThrows(CdekCalculationException.class,
                () -> CdekCalculatorHttpGateway.parse("{\"period_min\":1}")).getMessage());
        assertEquals("Некорректный ответ калькулятора СДЭК", assertThrows(CdekCalculationException.class,
                () -> CdekCalculatorHttpGateway.parse("<html>")).getMessage());
    }

    @Test
    void httpErrorCarriesCdekMessage() {
        when(auth.getAccessToken(KEYS)).thenReturn("tok");
        stub.respond(HttpStatus.BAD_REQUEST, "{\"errors\":[{\"code\":\"x\",\"message\":\"Неверный индекс\"}]}");

        CdekCalculationException e = assertThrows(CdekCalculationException.class,
                () -> gateway.calculate(KEYS, 136, FROM, TO, PACKAGES));

        assertEquals("Ошибка API СДЭК (HTTP 400): Неверный индекс", e.getMessage());
    }

    @Test
    void noTokenMeansNoRequest() {
        when(auth.getAccessToken(KEYS)).thenReturn(null);

        CdekCalculationException e = assertThrows(CdekCalculationException.class,
                () -> gateway.calculate(KEYS, 136, FROM, TO, PACKAGES));

        assertEquals("Не удалось получить токен доступа СДЭК", e.getMessage());
        assertTrue(stub.requests().isEmpty());
    }
}
