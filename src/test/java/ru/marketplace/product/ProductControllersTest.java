package ru.marketplace.product;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.config.GlobalExceptionHandler;
import ru.marketplace.product.dto.MerchantPhotoDTO;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.product.dto.ProductVariantDTO;
import ru.marketplace.product.dto.PublicProductDTO;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контракт ручек каталога: пути, имена полей JSON, коды ответов и формат ошибок {"error": "..."}. */
class ProductControllersTest {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PHOTO_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VARIANT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ProductService productService = mock(ProductService.class);
    private final ProductPhotoService productPhotoService = mock(ProductPhotoService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Jackson как в Spring Boot: даты ISO-строками, а не числами
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MerchantProductController(productService, productPhotoService),
                        new PublicProductController(productService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    private static MerchantProductDTO merchantProduct() {
        return MerchantProductDTO.builder()
                .id(PRODUCT_ID)
                .name("Свеча")
                .description("")
                .photos(List.of(new MerchantPhotoDTO(PHOTO_ID, "https://s3.test/a.jpg")))
                .priceKopecks(149_000L)
                .crossedPriceKopecks(null)
                .active(true)
                .preorder(false)
                .orderNumber(0)
                .weightGrams(300)
                .lengthCm(null)
                .widthCm(null)
                .heightCm(null)
                .variants(List.of(new ProductVariantDTO(VARIANT_ID, "80 мл", 99_000L)))
                .createdAt(Instant.parse("2026-10-01T09:00:00Z"))
                .updatedAt(Instant.parse("2026-10-01T09:30:00Z"))
                .build();
    }

    private static final String MERCHANT_JSON = """
            {"id":"11111111-1111-1111-1111-111111111111","name":"Свеча","description":"",
             "photos":[{"id":"22222222-2222-2222-2222-222222222222","url":"https://s3.test/a.jpg"}],
             "priceKopecks":149000,"crossedPriceKopecks":null,"active":true,"preorder":false,"orderNumber":0,
             "weightGrams":300,"lengthCm":null,"widthCm":null,"heightCm":null,
             "variants":[{"id":"33333333-3333-3333-3333-333333333333","label":"80 мл","priceKopecks":99000}],
             "createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:30:00Z"}
            """;

    private static final String VALID_BODY = """
            {"name":"Свеча","description":"","priceKopecks":149000,"crossedPriceKopecks":199000,
             "active":true,"preorder":false,"weightGrams":300,"lengthCm":null,"widthCm":null,"heightCm":null,
             "variants":[{"id":"33333333-3333-3333-3333-333333333333","label":"80 мл","priceKopecks":99000},
                         {"id":null,"label":"200 мл","priceKopecks":199000}]}
            """;

    // ---------- мерчант ----------

    @Test
    void listReturnsMerchantProductsWithExactFields() throws Exception {
        when(productService.getMerchantProducts()).thenReturn(List.of(merchantProduct()));

        mockMvc.perform(get("/api/merchant/products"))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + MERCHANT_JSON + "]", true));
    }

    @Test
    void getByIdAndNotFoundFormat() throws Exception {
        when(productService.getMerchantProduct(PRODUCT_ID)).thenReturn(merchantProduct());
        UUID foreign = UUID.randomUUID();
        when(productService.getMerchantProduct(foreign))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар не найден"));

        mockMvc.perform(get("/api/merchant/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));
        mockMvc.perform(get("/api/merchant/products/" + foreign))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Товар не найден\"}", true));
    }

    @Test
    void createParsesFullForm() throws Exception {
        when(productService.create(any())).thenReturn(merchantProduct());

        mockMvc.perform(post("/api/merchant/products").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));

        ArgumentCaptor<ProductSaveRequestDTO> captor = ArgumentCaptor.forClass(ProductSaveRequestDTO.class);
        verify(productService).create(captor.capture());
        ProductSaveRequestDTO request = captor.getValue();
        assertEquals("Свеча", request.getName());
        assertEquals("", request.getDescription());
        assertEquals(149_000L, request.getPriceKopecks());
        assertEquals(199_000L, request.getCrossedPriceKopecks());
        assertEquals(300, request.getWeightGrams());
        assertEquals(2, request.getVariants().size());
        assertEquals(VARIANT_ID, request.getVariants().get(0).getId());
        assertNull(request.getVariants().get(1).getId());
        assertEquals("200 мл", request.getVariants().get(1).getLabel());
    }

    @Test
    void validationErrorsAreRussianAndNamed() throws Exception {
        assertCreateRejected(VALID_BODY.replace("\"name\":\"Свеча\"", "\"name\":\"  \""), "name: укажите название товара");
        assertCreateRejected(VALID_BODY.replace("\"description\":\"\",", ""), "description: описание обязательно (может быть пустым)");
        assertCreateRejected(VALID_BODY.replace("\"priceKopecks\":149000", "\"priceKopecks\":99"), "priceKopecks: цена не меньше 1 ₽");
        assertCreateRejected(VALID_BODY.replace("\"active\":true,", ""), "active: укажите, продаётся ли товар");
        assertCreateRejected(VALID_BODY.replace("\"preorder\":false", "\"preorder\":null"), "preorder: укажите, предзаказ ли это");
        assertCreateRejected(VALID_BODY.replace("\"weightGrams\":300", "\"weightGrams\":0"), "weightGrams: вес от 1 до 100000 г");
        assertCreateRejected(VALID_BODY.replace("\"heightCm\":null", "\"heightCm\":100001"), "heightCm: высота от 1 до 100000 см");
        assertCreateRejected(VALID_BODY.replace("\"label\":\"200 мл\"", "\"label\":\"\""), "variants[1].label: укажите название варианта");
        assertCreateRejected(VALID_BODY.replace("\"priceKopecks\":99000", "\"priceKopecks\":50"), "variants[0].priceKopecks: цена варианта не меньше 1 ₽");
        assertCreateRejected(VALID_BODY.replace("\"name\":\"Свеча\"", "\"name\":\"" + "x".repeat(256) + "\""), "name: название не длиннее 255 символов");
        assertCreateRejected("{\"name\":", "Некорректный запрос");
        verifyNoInteractions(productService);
    }

    private void assertCreateRejected(String body, String error) throws Exception {
        mockMvc.perform(post("/api/merchant/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"" + error + "\"}", true));
    }

    @Test
    void updateGoesToProductId() throws Exception {
        when(productService.update(eq(PRODUCT_ID), any())).thenReturn(merchantProduct());

        mockMvc.perform(put("/api/merchant/products/" + PRODUCT_ID).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));
        verify(productService).update(eq(PRODUCT_ID), any(ProductSaveRequestDTO.class));
    }

    @Test
    void deleteAnswers204WithoutBody() throws Exception {
        mockMvc.perform(delete("/api/merchant/products/" + PRODUCT_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(productService).delete(PRODUCT_ID);
    }

    @Test
    void catalogOrderIsNotMistakenForProductId() throws Exception {
        UUID second = UUID.randomUUID();
        when(productService.reorder(List.of(PRODUCT_ID, second))).thenReturn(List.of(merchantProduct()));

        mockMvc.perform(put("/api/merchant/products/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + PRODUCT_ID + "\",\"" + second + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + MERCHANT_JSON + "]", true));
        verify(productService, never()).update(any(), any());

        mockMvc.perform(put("/api/merchant/products/order").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"productIds: не передан порядок товаров\"}", true));
    }

    @Test
    void malformedProductIdIs400() throws Exception {
        mockMvc.perform(get("/api/merchant/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Некорректное значение параметра: id\"}", true));
    }

    // ---------- фото ----------

    @Test
    void presignReturnsUploadUrlAndKey() throws Exception {
        when(productPhotoService.presign(PRODUCT_ID, "a.jpg", "image/jpeg", null))
                .thenReturn(new PresignUploadResponseDTO("https://upload", "shops/s/products/p/u.jpg"));
        when(productPhotoService.presign(PRODUCT_ID, "a.jpg", "image/jpeg", 524288L))
                .thenReturn(new PresignUploadResponseDTO("https://upload-sized", "shops/s/products/p/v.jpg"));

        // Без size — как шлёт фронт сейчас (обратная совместимость)
        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"a.jpg\",\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"uploadUrl\":\"https://upload\",\"key\":\"shops/s/products/p/u.jpg\"}", true));
        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"a.jpg\",\"contentType\":\"image/jpeg\",\"size\":524288}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"uploadUrl\":\"https://upload-sized\",\"key\":\"shops/s/products/p/v.jpg\"}", true));
        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"a.jpg\",\"contentType\":\"image/jpeg\",\"size\":10485761}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"size: Файл больше 10 МБ\"}", true));
        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"a.jpg\",\"contentType\":\"image/jpeg\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"size: пустой файл\"}", true));

        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos/presign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"a.jpg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"contentType: не указан тип файла\"}", true));
    }

    @Test
    void registerDeleteAndReorderPhotos() throws Exception {
        when(productPhotoService.register(PRODUCT_ID, "shops/s/products/p/u.jpg")).thenReturn(merchantProduct());
        when(productPhotoService.delete(PRODUCT_ID, PHOTO_ID)).thenReturn(merchantProduct());
        when(productPhotoService.reorder(PRODUCT_ID, List.of(PHOTO_ID))).thenReturn(merchantProduct());

        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"shops/s/products/p/u.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));
        mockMvc.perform(delete("/api/merchant/products/" + PRODUCT_ID + "/photos/" + PHOTO_ID))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));
        mockMvc.perform(put("/api/merchant/products/" + PRODUCT_ID + "/photos/order")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[\"" + PHOTO_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json(MERCHANT_JSON, true));

        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"key: не указан ключ файла\"}", true));
        verify(productPhotoService, times(1)).register(any(), anyString());
    }

    @Test
    void photoLimitErrorIsPassedThrough() throws Exception {
        when(productPhotoService.register(eq(PRODUCT_ID), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, ProductPhotoService.PHOTO_LIMIT));

        mockMvc.perform(post("/api/merchant/products/" + PRODUCT_ID + "/photos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"У товара может быть не больше 20 фото\"}", true));
    }

    // ---------- витрина ----------

    private static PublicProductDTO publicProduct() {
        return PublicProductDTO.builder()
                .id(PRODUCT_ID)
                .name("Свеча")
                .description("Соевый воск")
                .photos(List.of("https://s3.test/a.jpg"))
                .priceKopecks(150_000L)
                .crossedPriceKopecks(200_000L)
                .discountPercent(25)
                .preorder(false)
                .variants(List.of(new ProductVariantDTO(VARIANT_ID, "80 мл", 99_000L)))
                .build();
    }

    private static final String PUBLIC_JSON = """
            {"id":"11111111-1111-1111-1111-111111111111","name":"Свеча","description":"Соевый воск",
             "photos":["https://s3.test/a.jpg"],"priceKopecks":150000,"crossedPriceKopecks":200000,
             "discountPercent":25,"preorder":false,
             "variants":[{"id":"33333333-3333-3333-3333-333333333333","label":"80 мл","priceKopecks":99000}]}
            """;

    @Test
    void storefrontProductsHaveExactPublicFields() throws Exception {
        when(productService.getStorefrontProducts("candles")).thenReturn(List.of(publicProduct()));
        when(productService.getStorefrontProduct("candles", PRODUCT_ID.toString())).thenReturn(publicProduct());

        mockMvc.perform(get("/api/public/shops/candles/products"))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + PUBLIC_JSON + "]", true));
        mockMvc.perform(get("/api/public/shops/candles/products/" + PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(content().json(PUBLIC_JSON, true));
    }

    @Test
    void storefrontMalformedIdReachesServiceForFriendly404() throws Exception {
        when(productService.getStorefrontProduct("candles", "old-link"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар не найден"));

        mockMvc.perform(get("/api/public/shops/candles/products/old-link"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Товар не найден\"}", true));
    }
}
