package ru.marketplace.order;

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
import ru.marketplace.common.PageDTO;
import ru.marketplace.config.GlobalExceptionHandler;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.order.dto.FulfillmentUpdateRequestDTO;
import ru.marketplace.order.dto.MerchantOrderDTO;
import ru.marketplace.order.dto.MerchantOrderItemDTO;
import ru.marketplace.order.dto.ProductSalesDTO;
import ru.marketplace.order.dto.SalesReportDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт ручек заказов и аналитики: имена полей, даты ISO-строкой, умолчания фильтров, ошибки {"error": "..."}. */
class MerchantOrderControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final MerchantOrderDTO ORDER = new MerchantOrderDTO("AB12CD",
            Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:05:00Z"),
            PaymentStatus.PAID, FulfillmentStatus.SHIPPED, "1234567890",
            "Анна", "+79991234567", "anna@example.com", "MSK123", "Москва", "ул. Тверская, 1", null,
            100_000, List.of(new MerchantOrderItemDTO(PRODUCT_ID, "Свеча", 2, 50_000)));
    private static final String ORDER_JSON = """
            {"publicId":"AB12CD","createdAt":"2026-10-01T09:00:00Z","paidAt":"2026-10-01T09:05:00Z",
             "paymentStatus":"PAID","fulfillmentStatus":"SHIPPED","trackingNumber":"1234567890",
             "customerName":"Анна","customerPhone":"+79991234567","customerEmail":"anna@example.com",
             "pvzCode":"MSK123","pvzCity":"Москва","pvzAddress":"ул. Тверская, 1","comment":null,
             "itemsTotalKopecks":100000,
             "items":[{"productId":"33333333-3333-3333-3333-333333333333","productName":"Свеча","quantity":2,"unitPriceKopecks":50000}]}
            """;

    private final MerchantOrderService orderService = mock(MerchantOrderService.class);
    private final SalesReportService reportService = mock(SalesReportService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Jackson как в Spring Boot: Instant и LocalDate — ISO-строкой, а не числом/массивом
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new MerchantOrderController(orderService, reportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    @Test
    void listDefaultsToPaidAllFirstPageOf20() throws Exception {
        when(orderService.list(any(), any(), anyInt(), anyInt())).thenReturn(new PageDTO<>(List.of(ORDER), 1, 0, 20));

        mockMvc.perform(get("/api/merchant/orders"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[" + ORDER_JSON + "],\"total\":1,\"page\":0,\"size\":20}", true));

        verify(orderService).list("PAID", "ALL", 0, 20);
    }

    @Test
    void listPassesFilters() throws Exception {
        when(orderService.list(any(), any(), anyInt(), anyInt())).thenReturn(new PageDTO<>(List.of(), 0, 2, 50));

        mockMvc.perform(get("/api/merchant/orders?payment=ALL&fulfillment=NEW&page=2&size=50"))
                .andExpect(status().isOk());

        verify(orderService).list("ALL", "NEW", 2, 50);
    }

    @Test
    void invalidFilterIs400Json() throws Exception {
        when(orderService.list(any(), any(), anyInt(), anyInt()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный фильтр оплаты: X"));

        mockMvc.perform(get("/api/merchant/orders?payment=X"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Некорректный фильтр оплаты: X\"}", true));
    }

    @Test
    void updateFulfillmentReadsBody() throws Exception {
        when(orderService.updateFulfillment(eq("AB12CD"), any())).thenReturn(ORDER);

        mockMvc.perform(put("/api/merchant/orders/AB12CD/fulfillment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillmentStatus\":\"SHIPPED\",\"trackingNumber\":\"1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json(ORDER_JSON, true));

        verify(orderService).updateFulfillment(eq("AB12CD"), argThat((FulfillmentUpdateRequestDTO r) ->
                "SHIPPED".equals(r.getFulfillmentStatus()) && "1234567890".equals(r.getTrackingNumber())));
    }

    @Test
    void notPaidOrderIs409Json() throws Exception {
        when(orderService.updateFulfillment(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Статус исполнения можно менять только у оплаченного заказа"));

        mockMvc.perform(put("/api/merchant/orders/AB12CD/fulfillment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillmentStatus\":\"SHIPPED\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"Статус исполнения можно менять только у оплаченного заказа\"}", true));
    }

    @Test
    void salesReportHasExactContractFields() throws Exception {
        when(reportService.getReport(any(), any())).thenReturn(new SalesReportDTO("candles", "Свечная лавка",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), 2, 3, 150_000,
                List.of(new ProductSalesDTO("Свеча", 3, 150_000))));

        mockMvc.perform(get("/api/merchant/analytics/sales?from=2026-10-01&to=2026-10-07"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"shopSlug":"candles","shopName":"Свечная лавка","from":"2026-10-01","to":"2026-10-07",
                         "ordersCount":2,"itemsCount":3,"totalKopecks":150000,
                         "products":[{"productName":"Свеча","quantity":3,"totalKopecks":150000}]}
                        """, true));

        verify(reportService).getReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7));
    }

    @Test
    void salesReportBadOrMissingDateIs400() throws Exception {
        mockMvc.perform(get("/api/merchant/analytics/sales?from=01.10.2026&to=2026-10-07"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/merchant/analytics/sales?from=2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Не указан параметр: to\"}", true));
        verifyNoInteractions(reportService);
    }
}
