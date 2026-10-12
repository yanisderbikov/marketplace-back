package ru.marketplace.cdek;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.cdek.dto.DeliveryCostRequestDTO;
import ru.marketplace.cdek.dto.DeliveryCostResponseDTO;
import ru.marketplace.auth.MutableClock;
import ru.marketplace.common.PublicRateLimiter;
import ru.marketplace.config.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт публичных ручек СДЭК: имена полей, стоимость числом в рублях, ошибки {"error": "..."}. */
class CdekPvzControllerTest {

    private static final UUID PRODUCT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VARIANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final CdekPvzService pvzService = mock(CdekPvzService.class);
    private final CdekDeliveryCostService costService = mock(CdekDeliveryCostService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().build());
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new CdekPvzController(pvzService, costService,
                        new PublicRateLimiter(new MutableClock(Instant.parse("2026-10-01T09:00:00Z")))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .setValidator(validator)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    @Test
    void pvzSearchHasExactContractFields() throws Exception {
        when(pvzService.search("тверская")).thenReturn(List.of(CdekPvzDTO.builder()
                .code("MSK1").name("На Тверской").countryCode("RU").region("Москва").city("Москва")
                .postalCode("101000").address("ул. Тверская, 1").fullAddress("Россия, Москва, ул. Тверская, 1")
                .workTime("Пн-Вс 10-21").longitude(37.61).latitude(55.75).build()));

        mockMvc.perform(get("/api/public/cdek/pvz").param("query", "тверская"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"code":"MSK1","name":"На Тверской","countryCode":"RU","region":"Москва","city":"Москва",
                          "postalCode":"101000","address":"ул. Тверская, 1","fullAddress":"Россия, Москва, ул. Тверская, 1",
                          "workTime":"Пн-Вс 10-21","longitude":37.61,"latitude":55.75}]
                        """, true));
    }

    @Test
    void pvzWithoutQueryIsEmptyList() throws Exception {
        when(pvzService.search(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/public/cdek/pvz"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }

    @Test
    void deliveryCostIsLimitedPerForwardedIp() throws Exception {
        when(costService.calculate(eq("candles"), any()))
                .thenReturn(new DeliveryCostResponseDTO(new BigDecimal("450.00"), 2, 4, 136));
        String body = "{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}]}";
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                            .header("X-Forwarded-For", "10.1.1." + (i % 250) + ", 203.0.113.7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"Слишком много расчётов доставки. Попробуйте позже\"}", true));
        verify(costService, org.mockito.Mockito.times(120)).calculate(eq("candles"), any());
    }

    @Test
    void deliveryCostReturnsRublesNumberAndPassesSlug() throws Exception {
        when(costService.calculate(eq("candles"), any()))
                .thenReturn(new DeliveryCostResponseDTO(new BigDecimal("450.00"), 2, 4, 136));

        mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"variantId\":\""
                                + VARIANT + "\",\"quantity\":2},{\"productId\":\"" + PRODUCT + "\",\"variantId\":null,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"cost\":450.00,\"periodMin\":2,\"periodMax\":4,\"tariffCode\":136}", true))
                .andExpect(jsonPath("$.cost").isNumber());

        org.mockito.ArgumentCaptor<DeliveryCostRequestDTO> captor = org.mockito.ArgumentCaptor.forClass(DeliveryCostRequestDTO.class);
        verify(costService).calculate(eq("candles"), captor.capture());
        assertEquals("MSK1", captor.getValue().getPvzCode());
        assertEquals(2, captor.getValue().getItems().size());
        assertEquals(VARIANT, captor.getValue().getItems().get(0).getVariantId());
        assertEquals(2, captor.getValue().getItems().get(0).getQuantity());
    }

    @Test
    void cdekFailureIs502WithError() throws Exception {
        when(costService.calculate(eq("candles"), any()))
                .thenThrow(new CdekCalculationException("Невозможно осуществить доставку по этому направлению"));

        mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}]}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"error\":\"Невозможно осуществить доставку по этому направлению\"}", true));
    }

    @Test
    void shopWithoutSenderPvzIs409() throws Exception {
        when(costService.calculate(eq("candles"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Магазин пока не указал пункт отправки СДЭК"));

        mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"Магазин пока не указал пункт отправки СДЭК\"}", true));
    }

    @Test
    void invalidRequestIs400WithoutCallingCdek() throws Exception {
        String[] bodies = {
                "{\"pvzCode\":\"\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":1}]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[{\"quantity\":1}]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":0}]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"" + PRODUCT + "\",\"quantity\":100}]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[null]}",
                "{\"pvzCode\":\"MSK1\",\"items\":[{\"productId\":\"not-a-uuid\",\"quantity\":1}]}",
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/public/shops/candles/delivery-cost")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isString());
        }
        verify(costService, never()).calculate(any(), any());
    }
}
