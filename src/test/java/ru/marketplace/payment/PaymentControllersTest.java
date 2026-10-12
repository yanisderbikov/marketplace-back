package ru.marketplace.payment;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;
import ru.marketplace.common.PublicRateLimiter;
import ru.marketplace.config.GlobalExceptionHandler;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.payment.dto.CheckoutRequestDTO;
import ru.marketplace.payment.dto.CheckoutResponseDTO;
import ru.marketplace.payment.dto.OrderStatusDTO;
import ru.marketplace.payment.dto.OrderStatusItemDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт ручек оплаты: пути, имена полей JSON, ответ вебхука «OK»/«fail» и формат ошибок {"error": "..."}. */
class PaymentControllersTest {

    private final CheckoutService checkoutService = mock(CheckoutService.class);
    private final OrderPaymentService orderPaymentService = mock(OrderPaymentService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CheckoutController(checkoutService,
                                new PublicRateLimiter(new MutableClock(Instant.parse("2026-10-01T09:00:00Z")))),
                        new TinkoffWebhookController(orderPaymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8), json)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    // ── Вебхук ──

    @Test
    void acceptedNotificationAnswersPlainOk() throws Exception {
        String body = "{\"OrderId\":\"A1B2C3\",\"Status\":\"CONFIRMED\"}";
        when(orderPaymentService.acceptTinkoffNotification(body)).thenReturn(true);

        mockMvc.perform(post("/api/public/payment/tinkoff-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("OK"));
    }

    @Test
    void rejectedNotificationAnswersFail() throws Exception {
        when(orderPaymentService.acceptTinkoffNotification(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/public/payment/tinkoff-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"OrderId\":\"A1B2C3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("fail"));
    }

    @Test
    void emptyNotificationAnswersFailWithoutProcessing() throws Exception {
        mockMvc.perform(post("/api/public/payment/tinkoff-webhook").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("fail"));

        verify(orderPaymentService, never()).acceptTinkoffNotification(anyString());
    }

    // ── Оформление ──

    private static String checkoutJson(int quantity) {
        return """
                {"items":[{"productId":"%s","variantId":null,"quantity":%d}],
                 "fullName":"Анна","phone":"+79991234567","email":" anna@example.com ","pvzCode":"SPB42",
                 "pvzCity":"Санкт-Петербург","pvzAddress":"Невский, 1","comment":null,
                 "marketingConsent":false,"acceptTerms":true,
                 "returnUrl":"https://market.example/shop/candles/success"}
                """.formatted(UUID.randomUUID(), quantity);
    }

    @Test
    void checkoutReturnsOrderAndPaymentUrl() throws Exception {
        when(checkoutService.checkout(eq("candles"), any()))
                .thenReturn(new CheckoutResponseDTO("A1B2C3", "https://securepay.tinkoff.ru/new/abc"));

        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(2)))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"orderPublicId\":\"A1B2C3\",\"paymentUrl\":\"https://securepay.tinkoff.ru/new/abc\"}", true));

        ArgumentCaptor<CheckoutRequestDTO> request = ArgumentCaptor.forClass(CheckoutRequestDTO.class);
        verify(checkoutService).checkout(eq("candles"), request.capture());
        assertEquals("anna@example.com", request.getValue().getEmail());
        assertEquals(2, request.getValue().getItems().get(0).getQuantity());
        assertEquals(Boolean.TRUE, request.getValue().getAcceptTerms());
    }

    @Test
    void checkoutValidatesQuantityBeforeService() throws Exception {
        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(100)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"items[0].quantity: Количество — от 1 до 99\"}", true));

        verify(checkoutService, never()).checkout(anyString(), any());
    }

    @Test
    void checkoutRejectsEmptyCart() throws Exception {
        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(1).replaceFirst("\\[\\{.*?}]", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"items: Корзина пуста\"}", true));
    }

    @Test
    void bankFailureIsJsonError502() throws Exception {
        when(checkoutService.checkout(eq("candles"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось создать платёж: Неверный токен."));

        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(1)))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"error\":\"Не удалось создать платёж: Неверный токен.\"}", true));
    }

    @Test
    void checkoutIsLimitedPerForwardedIp() throws Exception {
        when(checkoutService.checkout(eq("candles"), any()))
                .thenReturn(new CheckoutResponseDTO("A1B2C3", "https://securepay.tinkoff.ru/new/abc"));
        for (int i = 0; i < 30; i++) {
            // Левую часть X-Forwarded-For клиент подставляет сам — ключ лимита всё равно адрес от прокси
            mockMvc.perform(post("/api/public/shops/candles/checkout")
                            .header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(checkoutJson(1)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(1)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"Слишком много заказов с вашего адреса. Попробуйте позже\"}", true));
        verify(checkoutService, org.mockito.Mockito.times(30)).checkout(eq("candles"), any());

        mockMvc.perform(post("/api/public/shops/candles/checkout")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutJson(1)))
                .andExpect(status().isOk());
    }

    // ── Статус ──

    @Test
    void statusIsLimitedPerIp() throws Exception {
        when(checkoutService.orderStatus("A1B2C3")).thenReturn(new OrderStatusDTO("A1B2C3", "candles", "Свечи",
                PaymentStatus.PAID, 178000L, List.of()));
        for (int i = 0; i < 600; i++) {
            mockMvc.perform(get("/api/public/orders/A1B2C3/status").with(r -> {
                r.setRemoteAddr("203.0.113.7");
                return r;
            })).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/public/orders/A1B2C3/status").with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"Слишком много запросов статуса заказа. Попробуйте позже\"}", true));
    }

    @Test
    void statusHasContractFieldNames() throws Exception {
        when(checkoutService.orderStatus("A1B2C3")).thenReturn(new OrderStatusDTO("A1B2C3", "candles", "Свечи",
                PaymentStatus.AWAITING_PAYMENT, 178000L, List.of(new OrderStatusItemDTO("Свеча", 2, 89000L))));

        mockMvc.perform(get("/api/public/orders/A1B2C3/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"publicId":"A1B2C3","shopSlug":"candles","shopName":"Свечи",
                         "paymentStatus":"AWAITING_PAYMENT","itemsTotalKopecks":178000,
                         "items":[{"productName":"Свеча","quantity":2,"unitPriceKopecks":89000}]}
                        """, true));
    }
}
