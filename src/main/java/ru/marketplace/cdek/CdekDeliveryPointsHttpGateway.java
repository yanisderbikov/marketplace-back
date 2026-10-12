package ru.marketplace.cdek;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import ru.marketplace.cdek.dto.CdekPvzDTO;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Загрузка всех ПВЗ СДЭК одним запросом /deliverypoints?type=PVZ (все страны присутствия).
 * Ответ — JSON-массив на сотни мегабайт, поэтому разбираем его потоково, по одному пункту,
 * а не строим дерево всего ответа (лимит буфера задан в бине cdekWebClient).
 */
@Component
class CdekDeliveryPointsHttpGateway implements CdekDeliveryPointsGateway {

    private static final Logger logger = LoggerFactory.getLogger(CdekDeliveryPointsHttpGateway.class);
    private static final String DELIVERY_POINTS_PATH = "/deliverypoints";
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CdekAuthService cdekAuthService;
    private final WebClient webClient;

    CdekDeliveryPointsHttpGateway(CdekAuthService cdekAuthService,
                                  @Qualifier("cdekWebClient") WebClient webClient) {
        this.cdekAuthService = cdekAuthService;
        this.webClient = webClient;
    }

    @Override
    public List<CdekPvzDTO> fetchAllPickupPoints(CdekCredentials credentials) {
        String token = cdekAuthService.getAccessToken(credentials);
        if (token == null) {
            logger.warn("ПВЗ СДЭК: не удалось получить токен доступа");
            return List.of();
        }
        try {
            long started = System.currentTimeMillis();
            // Байты, а не String: строка с кириллицей заняла бы в памяти вдвое больше самого ответа
            byte[] body = webClient.get()
                    .uri(uri -> uri.path(DELIVERY_POINTS_PATH).queryParam("type", "PVZ").build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(TIMEOUT)
                    .block();
            if (body == null || body.length == 0) {
                logger.warn("ПВЗ СДЭК: пустой ответ deliverypoints");
                return List.of();
            }
            List<CdekPvzDTO> result = parse(body);
            logger.info("ПВЗ СДЭК: получено {} пунктов за {} мс", result.size(), System.currentTimeMillis() - started);
            return result;
        } catch (Exception e) {
            logger.error("ПВЗ СДЭК: ошибка загрузки полного списка: {}", e.getMessage());
            return List.of();
        }
    }

    static List<CdekPvzDTO> parse(byte[] body) throws IOException {
        List<CdekPvzDTO> result = new ArrayList<>(65_536);
        try (JsonParser parser = MAPPER.getFactory().createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("ответ deliverypoints — не JSON-массив");
            }
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
                if (token != JsonToken.START_OBJECT) {
                    // Не объект (null, число…) — пропускаем элемент целиком, остальные пункты не теряем
                    parser.skipChildren();
                    continue;
                }
                CdekPvzDTO dto = mapPoint(MAPPER.readTree(parser));
                if (dto != null) {
                    result.add(dto);
                }
            }
        }
        return result;
    }

    private static CdekPvzDTO mapPoint(JsonNode point) {
        String code = asString(point, "code");
        if (code == null) {
            return null;
        }
        JsonNode location = point.has("location") && point.get("location").isObject()
                ? point.get("location")
                : MAPPER.createObjectNode();
        return CdekPvzDTO.builder()
                .code(code)
                .name(asString(point, "name"))
                .workTime(asString(point, "work_time"))
                .countryCode(asString(location, "country_code"))
                .region(asString(location, "region"))
                .city(asString(location, "city"))
                .postalCode(asString(location, "postal_code"))
                .address(asString(location, "address"))
                .fullAddress(asString(location, "address_full"))
                .longitude(asDouble(location, "longitude"))
                .latitude(asDouble(location, "latitude"))
                .build();
    }

    private static String asString(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static Double asDouble(JsonNode obj, String key) {
        JsonNode value = obj.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.valueOf(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
