package ru.marketplace.auth;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.dto.LoginResponseDTO;
import ru.marketplace.auth.dto.MeDTO;
import ru.marketplace.auth.dto.MeShopDTO;
import ru.marketplace.config.GlobalExceptionHandler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт ручек входа: пути, имена полей JSON и формат ошибок {"error": "..."}. */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 3, new MutableClock(Instant.parse("2026-10-01T09:00:00Z")));
        // Jackson как в Spring Boot: даты ISO-строками, а не числами
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, limiter))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    private static MockHttpServletRequestBuilder verifyCode(String body, String forwardedFor) {
        return post("/api/public/auth/verify-code")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .content(body);
    }

    private static MockHttpServletRequestBuilder requestCode(String body, String forwardedFor) {
        return post("/api/public/auth/request-code")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .content(body);
    }

    @Test
    void requestCodeAnswersEmptyObject() throws Exception {
        mockMvc.perform(requestCode("{\"email\":\" Shop@Example.com \"}", "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}", true));

        // Пробелы обрезаны до валидации, регистр приводит сервис
        verify(authService).requestLoginCode("Shop@Example.com");
    }

    @Test
    void requestCodeIsLimitedPerForwardedIp() throws Exception {
        String body = "{\"email\":\"shop@example.com\"}";
        // Пограничный прокси дописывает реальный адрес в конец; подделанная левая часть лимит не обходит
        mockMvc.perform(requestCode(body, "1.1.1.1, 203.0.113.7")).andExpect(status().isOk());
        mockMvc.perform(requestCode(body, "2.2.2.2, 203.0.113.7")).andExpect(status().isOk());

        mockMvc.perform(requestCode(body, "3.3.3.3, 203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"" + LoginRateLimiter.LIMIT_MESSAGE + "\"}", true));
        mockMvc.perform(requestCode(body, "203.0.113.7, 198.51.100.1")).andExpect(status().isOk());

        verify(authService, times(3)).requestLoginCode("shop@example.com");
    }

    @Test
    void verifyCodeIsLimitedPerForwardedIp() throws Exception {
        when(authService.verifyLoginCode(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверный код. Осталось попыток: 4"));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(verifyCode("{\"email\":\"shop@example.com\",\"code\":\"000000\"}", i + ".1.1.1, 203.0.113.7"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(verifyCode("{\"email\":\"shop@example.com\",\"code\":\"000000\"}", "203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"" + LoginRateLimiter.VERIFY_LIMIT_MESSAGE + "\"}", true));
        verify(authService, times(3)).verifyLoginCode(anyString(), anyString());
        mockMvc.perform(verifyCode("{\"email\":\"shop@example.com\",\"code\":\"000000\"}", "198.51.100.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockedOutEmailIs429WithErrorJson() throws Exception {
        when(authService.verifyLoginCode(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, AuthServiceImpl.TOO_MANY_FAILS));

        mockMvc.perform(verifyCode("{\"email\":\"shop@example.com\",\"code\":\"000000\"}", "203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"" + AuthServiceImpl.TOO_MANY_FAILS + "\"}", true));
    }

    @Test
    void invalidEmailIsRejectedBeforeLimiterAndService() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(requestCode("{\"email\":\"not-an-email\"}", "203.0.113.7"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"email: Некорректная почта\"}", true));
        }
        verifyNoInteractions(authService);
        // Отбитые валидацией запросы лимит не расходуют
        mockMvc.perform(requestCode("{\"email\":\"shop@example.com\"}", "203.0.113.7")).andExpect(status().isOk());
    }

    @Test
    void mailFailureIsBadGatewayWithErrorJson() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось отправить письмо. Попробуйте ещё раз через минуту"))
                .when(authService).requestLoginCode(anyString());

        mockMvc.perform(requestCode("{\"email\":\"shop@example.com\"}", "203.0.113.7"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"error\":\"Не удалось отправить письмо. Попробуйте ещё раз через минуту\"}", true));
    }

    @Test
    void verifyCodeReturnsToken() throws Exception {
        when(authService.verifyLoginCode("shop@example.com", "048213")).thenReturn(new LoginResponseDTO("jwt"));

        mockMvc.perform(post("/api/public/auth/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"shop@example.com\",\"code\":\" 048213 \"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"token\":\"jwt\"}", true));
    }

    @Test
    void verifyCodeValidatesCodeFormat() throws Exception {
        mockMvc.perform(post("/api/public/auth/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"shop@example.com\",\"code\":\"12ab56\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"code: Код — 6 цифр\"}", true));
        verifyNoInteractions(authService);
    }

    @Test
    void wrongCodeErrorComesAsErrorJson() throws Exception {
        when(authService.verifyLoginCode(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверный код. Осталось попыток: 4"));

        mockMvc.perform(post("/api/public/auth/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"shop@example.com\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Неверный код. Осталось попыток: 4\"}", true));
    }

    @Test
    void meHasExactContractFields() throws Exception {
        UUID shopId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(authService.me()).thenReturn(new MeDTO("shop@example.com", "shop", false,
                new MeShopDTO(shopId, "candles", "Свечи", true)));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"email":"shop@example.com","name":"shop","superAdmin":false,
                         "shop":{"id":"11111111-2222-3333-4444-555555555555","slug":"candles","name":"Свечи","active":true}}
                        """, true));
    }

    @Test
    void meWithoutShopHasNullShop() throws Exception {
        when(authService.me()).thenReturn(new MeDTO("boss@example.com", "boss", true, null));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"email\":\"boss@example.com\",\"name\":\"boss\",\"superAdmin\":true,\"shop\":null}", true));
    }
}
