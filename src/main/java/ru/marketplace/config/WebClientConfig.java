package ru.marketplace.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * HTTP-клиенты внешних сервисов в одном месте.
 * Адреса СДЭК и NotiSend — константы (как в anyforms), адрес Т-Банка — из env:
 * на нём переключают боевой/тестовый контур.
 */
@Configuration
public class WebClientConfig {

    /** Базовый адрес API СДЭК v2: токен /oauth/token, ПВЗ /deliverypoints, калькулятор /calculator/tariff */
    public static final String CDEK_BASE_URL = "https://api.cdek.ru/v2";
    /** Полный список ПВЗ СДЭК приходит одним JSON на сотни мегабайт — дефолтных 256 КБ буфера не хватает */
    private static final int CDEK_MAX_BODY_BYTES = 512 * 1024 * 1024;

    private static final String NOTISEND_BASE_URL = "https://api.notisend.ru/v1";

    /** API ЮKassa v3: /payments, /payments/{id}, /me */
    public static final String YOOKASSA_BASE_URL = "https://api.yookassa.ru/v3";

    @Value("${payment.tinkoff.api.url}")
    private String tinkoffApiUrl;

    @Bean("tinkoffWebClient")
    public WebClient tinkoffWebClient() {
        return WebClient.builder()
                .baseUrl(tinkoffApiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** У ЮKassa один адрес и для боевых, и для тестовых магазинов: контур определяют ключи магазина. */
    @Bean("yookassaWebClient")
    public WebClient yookassaWebClient() {
        return WebClient.builder()
                .baseUrl(YOOKASSA_BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean("cdekWebClient")
    public WebClient cdekWebClient() {
        return WebClient.builder()
                .baseUrl(CDEK_BASE_URL)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(CDEK_MAX_BODY_BYTES))
                .build();
    }

    @Bean("notisendWebClient")
    public WebClient notisendWebClient() {
        // Системный (JVM) DNS-резолвер вместо netty-шного — перенесено из anyforms NotiSendEmailService как есть
        HttpClient http = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl(NOTISEND_BASE_URL)
                .build();
    }
}
