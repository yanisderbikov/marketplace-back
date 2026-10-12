package ru.marketplace.cdek;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WebClient без сети для тестов интеграции со СДЭК: отвечает заранее заданными ответами по очереди
 * и запоминает запросы вместе с телом — так проверяем и разбор ответа, и что именно уходит в СДЭК.
 */
class CdekWebClientStub {

    /** writtenHeaders — заголовки, которые выставил сам кодек при записи тела (например, Content-Type формы) */
    record Recorded(ClientRequest request, HttpHeaders writtenHeaders, String body) {
    }

    private final Deque<ClientResponse> responses = new ArrayDeque<>();
    private final List<Recorded> requests = new ArrayList<>();

    final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.cdek.test/v2")
            .exchangeFunction(this::exchange)
            .build();

    CdekWebClientStub respond(HttpStatus status, String json) {
        responses.add(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(json)
                .build());
        return this;
    }

    List<Recorded> requests() {
        return requests;
    }

    Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    private Mono<ClientResponse> exchange(ClientRequest request) {
        MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
        written.getHeaders().putAll(request.headers());
        String body = write(request, written);
        requests.add(new Recorded(request, written.getHeaders(), body));
        ClientResponse next = responses.poll();
        if (next == null) {
            return Mono.error(new IllegalStateException("Нет заготовленного ответа для " + request.url()));
        }
        return Mono.just(next);
    }

    private static String write(ClientRequest request, MockClientHttpRequest mock) {
        request.body().insert(mock, new BodyInserter.Context() {
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
        return mock.getBodyAsString().defaultIfEmpty("").block();
    }
}
