package ru.marketplace.shop;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.config.GlobalExceptionHandler;
import ru.marketplace.shop.dto.AdminShopDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт ручек супер-админа: имена полей, даты ISO-строкой, ошибки {"error": "..."}. */
class AdminShopControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AdminShopDTO DTO = new AdminShopDTO(ID, "candles", "Свечи", true, "owner@example.com",
            Instant.parse("2026-10-01T09:00:00Z"), 12, 3, 357_000, false);
    private static final String DTO_JSON = """
            {"id":"11111111-1111-1111-1111-111111111111","slug":"candles","name":"Свечи","active":true,
             "ownerEmail":"owner@example.com","createdAt":"2026-10-01T09:00:00Z","productsCount":12,
             "paidOrdersCount":3,"paidTotalKopecks":357000,"acceptsOrders":false}
            """;

    private final AdminShopService service = mock(AdminShopService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Jackson как в Spring Boot: Instant — ISO-строкой, а не числом
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminShopController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    @Test
    void listHasExactContractFields() throws Exception {
        when(service.list()).thenReturn(List.of(DTO));

        mockMvc.perform(get("/api/admin/shops"))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + DTO_JSON + "]", true));
    }

    @Test
    void setActivePassesFlagAndReturnsShop() throws Exception {
        when(service.setActive(ID, false)).thenReturn(DTO);

        mockMvc.perform(put("/api/admin/shops/" + ID + "/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().json(DTO_JSON, true));
        verify(service).setActive(ID, false);
    }

    @Test
    void missingFlagIs400() throws Exception {
        mockMvc.perform(put("/api/admin/shops/" + ID + "/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"active: Укажите, включён ли магазин\"}", true));
        verify(service, never()).setActive(any(), anyBoolean());
    }

    @Test
    void malformedIdIs400() throws Exception {
        mockMvc.perform(put("/api/admin/shops/not-a-uuid/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Некорректное значение параметра: id\"}", true));
    }

    @Test
    void unknownShopIs404() throws Exception {
        when(service.setActive(ID, true)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден"));

        mockMvc.perform(put("/api/admin/shops/" + ID + "/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Магазин не найден\"}", true));
    }
}
