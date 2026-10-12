package ru.marketplace.cdek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.0 к API СДЭК: получение и кэширование access-токена (client_credentials).
 * Общий бин для калькулятора доставки и загрузки ПВЗ — чтобы не дублировать логику авторизации.
 * Аккаунта платформы нет: у каждого магазина свои ключи, поэтому и токен кэшируется на ключи магазина.
 */
@Service
public class CdekAuthService {

    /** Итог проверки ключей при сохранении: отказ СДЭК и недоступность СДЭК мерчанту объясняем по-разному */
    public enum Check { OK, REJECTED, UNAVAILABLE }

    private static final Logger logger = LoggerFactory.getLogger(CdekAuthService.class);
    private static final String TOKEN_PATH = "/oauth/token";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Обновляем токен за минуту до истечения, чтобы не отправить в СДЭК уже протухший */
    private static final long EXPIRY_MARGIN_SECONDS = 60;
    /** СДЭК всегда присылает expires_in; это срок на случай, если поле пропадёт из ответа (как в anyforms) */
    private static final long FALLBACK_EXPIRES_IN_SECONDS = 3600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record CachedToken(String token, Instant expiresAt) {
    }

    private record TokenResult(Check check, CachedToken token) {
    }

    private final WebClient webClient;
    private final Clock clock;

    /**
     * Ключ — ключи целиком (record сравнивает и секрет): сменил мерчант Secure password — старый токен не подойдёт.
     * Без общего lock: на время запроса в СДЭК не блокируем остальные магазины, гонка даёт лишь лишний токен
     */
    private final Map<CdekCredentials, CachedToken> tokens = new ConcurrentHashMap<>();

    public CdekAuthService(@Qualifier("cdekWebClient") WebClient webClient, Clock clock) {
        this.webClient = webClient;
        this.clock = clock;
    }

    /**
     * Возвращает валидный access-токен СДЭК для ключей магазина (из кэша или запросив новый). {@code null} при ошибке.
     */
    public String getAccessToken(CdekCredentials credentials) {
        CachedToken cached = tokens.get(credentials);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.token();
        }
        TokenResult result = requestToken(credentials);
        return result.token() == null ? null : result.token().token();
    }

    /** Проверка ключей перед сохранением в настройках магазина: СДЭК выдаёт по ним токен или нет. */
    public Check check(CdekCredentials credentials) {
        return requestToken(credentials).check();
    }

    private TokenResult requestToken(CdekCredentials credentials) {
        Instant now = clock.instant();
        try {
            // Форма собирается через BodyInserters, а не String.format как в anyforms:
            // так client_secret с «+», «&» или «=» корректно URL-кодируется
            String response = webClient.post()
                    .uri(TOKEN_PATH)
                    .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                            .with("client_id", credentials.clientId())
                            .with("client_secret", credentials.clientSecret()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();

            if (response != null && !response.isEmpty()) {
                JsonNode json = MAPPER.readTree(response);
                JsonNode token = json.get("access_token");
                if (token != null && token.isTextual() && !token.asText().isBlank()) {
                    JsonNode expiresIn = json.get("expires_in");
                    long ttl = expiresIn != null && expiresIn.canConvertToLong()
                            ? expiresIn.asLong()
                            : FALLBACK_EXPIRES_IN_SECONDS;
                    CachedToken cached = new CachedToken(token.asText(),
                            now.plusSeconds(Math.max(ttl - EXPIRY_MARGIN_SECONDS, 0)));
                    tokens.values().removeIf(t -> !now.isBefore(t.expiresAt()));
                    tokens.put(credentials, cached);
                    return new TokenResult(Check.OK, cached);
                }
                logger.error("Ошибка получения токена СДЭК (Account {}): {}", credentials.clientId(), response);
            }
        } catch (WebClientResponseException e) {
            logger.error("Ошибка получения токена СДЭК (Account {}): HTTP {}, ответ {}",
                    credentials.clientId(), e.getStatusCode().value(), e.getResponseBodyAsString());
            // 400/401/403 на /oauth/token — СДЭК не принял сами ключи; 5xx и прочее — сбой на его стороне
            if (e.getStatusCode().is4xxClientError()) {
                return new TokenResult(Check.REJECTED, null);
            }
        } catch (Exception e) {
            logger.error("Ошибка при получении токена доступа СДЭК (Account {}): {}",
                    credentials.clientId(), e.getMessage(), e);
        }
        return new TokenResult(Check.UNAVAILABLE, null);
    }
}
