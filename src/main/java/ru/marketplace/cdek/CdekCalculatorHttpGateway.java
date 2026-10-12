package ru.marketplace.cdek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.marketplace.cdek.dto.CdekLocation;
import ru.marketplace.cdek.dto.CdekPackage;
import ru.marketplace.cdek.dto.CdekTariffQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

@Component
class CdekCalculatorHttpGateway implements CdekCalculatorGateway {

    private static final Logger logger = LoggerFactory.getLogger(CdekCalculatorHttpGateway.class);
    private static final String TARIFF_PATH = "/calculator/tariff";
    private static final int ORDER_TYPE_ONLINE_SHOP = 1;
    private static final int CURRENCY_RUB = 1;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CdekAuthService cdekAuthService;
    private final WebClient webClient;

    CdekCalculatorHttpGateway(CdekAuthService cdekAuthService,
                              @Qualifier("cdekWebClient") WebClient webClient) {
        this.cdekAuthService = cdekAuthService;
        this.webClient = webClient;
    }

    @Override
    public CdekTariffQuote calculate(CdekCredentials credentials, int tariffCode, CdekLocation from, CdekLocation to,
                                     List<CdekPackage> packages) {
        String token = cdekAuthService.getAccessToken(credentials);
        if (token == null) {
            throw new CdekCalculationException("Не удалось получить токен доступа СДЭК");
        }
        String requestJson = buildRequest(tariffCode, from, to, packages).toString();
        logger.info("Калькулятор СДЭК: запрос {}", requestJson);
        String body;
        try {
            body = webClient.post()
                    .uri(TARIFF_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            String errorBody = e.getResponseBodyAsString();
            logger.error("Калькулятор СДЭК: HTTP {}, ответ {}", e.getStatusCode().value(), errorBody);
            throw new CdekCalculationException("Ошибка API СДЭК (HTTP " + e.getStatusCode().value() + "): "
                    + extractError(errorBody, e.getMessage()));
        } catch (Exception e) {
            logger.error("Калькулятор СДЭК: ошибка запроса: {}", e.getMessage());
            throw new CdekCalculationException("Ошибка обращения к СДЭК: " + e.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw new CdekCalculationException("Пустой ответ калькулятора СДЭК");
        }
        logger.info("Калькулятор СДЭК: ответ {}", body);
        return parse(body);
    }

    static ObjectNode buildRequest(int tariffCode, CdekLocation from, CdekLocation to, List<CdekPackage> packages) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("type", ORDER_TYPE_ONLINE_SHOP);
        request.put("currency", CURRENCY_RUB);
        request.put("tariff_code", tariffCode);
        request.set("from_location", location(from));
        request.set("to_location", location(to));
        ArrayNode packagesJson = request.putArray("packages");
        for (CdekPackage p : packages) {
            ObjectNode pkg = packagesJson.addObject();
            pkg.put("weight", p.weightGrams());
            pkg.put("length", p.lengthCm());
            pkg.put("width", p.widthCm());
            pkg.put("height", p.heightCm());
        }
        return request;
    }

    private static ObjectNode location(CdekLocation location) {
        ObjectNode json = MAPPER.createObjectNode();
        addIfPresent(json, "country_code", location.countryCode());
        addIfPresent(json, "postal_code", location.postalCode());
        addIfPresent(json, "city", location.city());
        addIfPresent(json, "address", location.address());
        return json;
    }

    private static void addIfPresent(ObjectNode json, String key, String value) {
        if (value != null && !value.isBlank()) {
            json.put(key, value);
        }
    }

    static CdekTariffQuote parse(String body) {
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new CdekCalculationException("Некорректный ответ калькулятора СДЭК");
        }
        String error = firstError(json);
        if (error != null) {
            throw new CdekCalculationException(error);
        }
        JsonNode sum = json == null ? null : json.get("delivery_sum");
        if (sum == null || sum.isNull()) {
            throw new CdekCalculationException("В ответе СДЭК нет стоимости доставки");
        }
        return new CdekTariffQuote(
                rubles(sum),
                intOrNull(json.get("period_min")),
                intOrNull(json.get("period_max")));
    }

    /** Рубли с копейками (2 знака), чтобы 450 и 450.0 из СДЭК отдавались фронту одинаково */
    private static BigDecimal rubles(JsonNode sum) {
        try {
            BigDecimal value = sum.isNumber() ? sum.decimalValue() : new BigDecimal(sum.asText().trim());
            return value.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new CdekCalculationException("В ответе СДЭК некорректная стоимость доставки");
        }
    }

    private static String extractError(String errorBody, String fallback) {
        if (errorBody == null || errorBody.isBlank()) {
            return fallback;
        }
        try {
            String error = firstError(MAPPER.readTree(errorBody));
            return error != null ? error : errorBody;
        } catch (Exception e) {
            return errorBody;
        }
    }

    private static String firstError(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        JsonNode errors = json.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            JsonNode first = errors.get(0);
            if (first.hasNonNull("message")) {
                return first.get("message").asText();
            }
            if (first.hasNonNull("code")) {
                return "Код ошибки СДЭК: " + first.get("code").asText();
            }
        }
        if (json.hasNonNull("message")) {
            return json.get("message").asText();
        }
        return null;
    }

    private static Integer intOrNull(JsonNode element) {
        return element == null || element.isNull() ? null : element.asInt();
    }
}
