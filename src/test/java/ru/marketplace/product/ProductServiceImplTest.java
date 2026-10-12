package ru.marketplace.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.ProductVariant;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.product.dto.PublicProductDTO;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.storage.S3FileStorage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static ru.marketplace.product.ProductFixtures.*;

class ProductServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final ShopLookup shopLookup = mock(ShopLookup.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductLockRepository productLockRepository = mock(ProductLockRepository.class);
    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private Shop shop;
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        shop = shop("candles");
        when(currentMerchant.shop()).thenReturn(shop);
        when(s3FileStorage.publicUrl(anyString())).thenAnswer(inv -> "https://s3.test/" + inv.getArgument(0));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productLockRepository.findOwnForUpdate(any(), any())).thenReturn(Optional.empty());
        ProductMapper mapper = new ProductMapper(s3FileStorage);
        ProductPhotoService photoService = new ProductPhotoService(currentMerchant, productRepository,
                productLockRepository, s3FileStorage, mapper,
                new ru.marketplace.storage.UploadRateLimiter(clock), clock);
        service = new ProductServiceImpl(currentMerchant, shopLookup, productRepository, productLockRepository,
                photoService, mapper, clock);
    }

    private void ownProductsLockable(Product... products) {
        for (Product product : products) {
            when(productLockRepository.findOwnForUpdate(product.getId(), shop.getId())).thenReturn(Optional.of(product));
        }
    }

    private static void assertStatus(HttpStatus status, String reason, org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call);
        assertEquals(status, e.getStatusCode());
        if (reason != null) {
            assertEquals(reason, e.getReason());
        }
    }

    // ---------- create ----------

    @Test
    void createPutsProductAtTheEndOfOwnCatalogWithVariantsInRequestOrder() {
        when(productRepository.findMaxOrderNumber(shop.getId())).thenReturn(Optional.of(4));
        ProductSaveRequestDTO request = request("  Свеча «Лаванда»  ");
        request.setDescription("Соевый воск");
        request.setCrossedPriceKopecks(199_000L);
        request.setWeightGrams(350);
        request.setVariants(new ArrayList<>(List.of(variant(null, " 80 мл ", 99_000L), variant(null, "200 мл", 199_000L))));

        MerchantProductDTO dto = service.create(request);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        Product product = saved.getValue();
        assertSame(shop, product.getShop());
        assertEquals("Свеча «Лаванда»", product.getName());
        assertEquals("Соевый воск", product.getDescription());
        assertEquals(5, product.getOrderNumber());
        assertEquals(NOW, product.getCreatedAt());
        assertEquals(List.of("80 мл", "200 мл"), labels(product));
        assertEquals(List.of(0, 1), product.getVariants().stream().map(ProductVariant::getOrderNumber).toList());
        assertTrue(product.getVariants().stream().allMatch(v -> v.getProduct() == product));
        verify(productRepository).flush();

        assertEquals(5, dto.orderNumber());
        assertEquals(199_000L, dto.crossedPriceKopecks());
        assertEquals(350, dto.weightGrams());
        assertEquals(List.of(), dto.photos());
    }

    @Test
    void firstProductOfShopGetsOrderNumberZero() {
        when(productRepository.findMaxOrderNumber(shop.getId())).thenReturn(Optional.empty());
        ProductSaveRequestDTO request = request("Свеча");
        request.setVariants(null);

        MerchantProductDTO dto = service.create(request);

        assertEquals(0, dto.orderNumber());
        assertEquals(List.of(), dto.variants());
    }

    @Test
    void crossedPriceMustBeHigherThanPrice() {
        ProductSaveRequestDTO request = request("Свеча");
        request.setCrossedPriceKopecks(149_000L);

        assertStatus(HttpStatus.BAD_REQUEST, ProductServiceImpl.CROSSED_PRICE_TOO_LOW, () -> service.create(request));
        request.setCrossedPriceKopecks(100_000L);
        assertStatus(HttpStatus.BAD_REQUEST, ProductServiceImpl.CROSSED_PRICE_TOO_LOW, () -> service.create(request));
        verify(productRepository, never()).save(any());
    }

    @Test
    void sameVariantTwiceIsRejected() {
        UUID id = UUID.randomUUID();
        ProductSaveRequestDTO request = request("Свеча");
        request.setVariants(new ArrayList<>(List.of(variant(id, "S", 100L), variant(id, "M", 200L))));

        assertStatus(HttpStatus.BAD_REQUEST, null, () -> service.create(request));
        request.setVariants(new ArrayList<>(Arrays.asList(variant(null, "S", 100L), null)));
        assertStatus(HttpStatus.BAD_REQUEST, null, () -> service.create(request));
        verify(productRepository, never()).save(any());
    }

    // ---------- update ----------

    @Test
    void updateReplacesAllFieldsAndVariantSetKeepingKnownVariantIds() {
        Product product = product(shop, "Старое", 2);
        product.setCrossedPriceKopecks(300_000L);
        product.setLengthCm(10);
        ProductVariant small = addVariant(product, "S", 100_000L, 0);
        ProductVariant medium = addVariant(product, "M", 120_000L, 1);
        ownProductsLockable(product);

        ProductSaveRequestDTO request = request("Новое");
        request.setActive(false);
        request.setPreorder(true);
        request.setVariants(new ArrayList<>(List.of(
                variant(medium.getId(), "M+", 130_000L),
                variant(null, "L", 150_000L),
                // id не этого товара (чужой или уже удалённый) — просто новый вариант, как в anyforms
                variant(UUID.randomUUID(), "XL", 170_000L))));

        MerchantProductDTO dto = service.update(product.getId(), request);

        assertEquals("Новое", product.getName());
        assertNull(product.getCrossedPriceKopecks(), "PUT заменяет карточку целиком: null очищает зачёркнутую цену");
        assertNull(product.getLengthCm());
        assertFalse(product.getActive());
        assertTrue(product.getPreorder());
        assertEquals(2, product.getOrderNumber(), "место в каталоге при сохранении не меняется");
        assertEquals(List.of("M+", "L", "XL"), labels(product));
        assertSame(medium, product.getVariants().get(0), "вариант с id обновлён на месте, а не пересоздан");
        assertEquals(130_000L, medium.getPriceKopecks());
        assertFalse(product.getVariants().contains(small), "не перечисленный вариант удаляется");
        assertEquals(List.of(0, 1, 2), product.getVariants().stream().map(ProductVariant::getOrderNumber).toList());
        assertEquals(NOW, product.getUpdatedAt());
        verify(productRepository).flush();
        verify(productRepository, never()).save(any());

        assertEquals(List.of("M+", "L", "XL"), dto.variants().stream().map(v -> v.label()).toList());
        assertFalse(dto.active());
    }

    @Test
    void emptyVariantListRemovesAllVariants() {
        Product product = product(shop, "Свеча", 0);
        addVariant(product, "S", 100_000L, 0);
        ownProductsLockable(product);

        service.update(product.getId(), request("Свеча"));

        assertTrue(product.getVariants().isEmpty());
    }

    @Test
    void foreignOrMissingProductIsNotFound() {
        // Lock-репозиторий ищет по id И магазину текущего мерчанта: товар чужого магазина туда не попадает
        UUID foreignId = UUID.randomUUID();
        when(productRepository.findByIdAndShopId(foreignId, shop.getId())).thenReturn(Optional.empty());

        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.update(foreignId, request("X")));
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.delete(foreignId));
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.getMerchantProduct(foreignId));
        verify(productLockRepository, times(2)).findOwnForUpdate(foreignId, shop.getId());
        verify(productRepository, never()).flush();
        verify(productRepository, never()).delete(any());
        verifyNoInteractions(s3FileStorage);
    }

    @Test
    void merchantListIsOwnShopCatalogInOrder() {
        Product first = product(shop, "Первый", 0);
        Product hidden = product(shop, "Выключенный", 1);
        hidden.setActive(false);
        when(productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId()))
                .thenReturn(List.of(first, hidden));

        List<MerchantProductDTO> list = service.getMerchantProducts();

        assertEquals(List.of("Первый", "Выключенный"), list.stream().map(MerchantProductDTO::name).toList());
        assertFalse(list.get(1).active());
    }

    // ---------- delete ----------

    @Test
    void deleteRemovesProductAndItsPhotoObjects() {
        Product product = product(shop, "Свеча", 0);
        ProductPhoto a = addPhoto(product, 0);
        ProductPhoto b = addPhoto(product, 1);
        ownProductsLockable(product);

        service.delete(product.getId());

        verify(productRepository).delete(product);
        verify(productRepository).flush();
        // Вне транзакции (юнит-тест) — сразу; в приложении — после коммита
        verify(s3FileStorage).delete(a.getS3Key());
        verify(s3FileStorage).delete(b.getS3Key());
    }

    // ---------- reorder ----------

    @Test
    void reorderSetsOrderNumberToIndex() {
        Product a = product(shop, "A", 0);
        Product b = product(shop, "B", 1);
        Product c = product(shop, "C", 2);
        when(productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId())).thenReturn(List.of(a, b, c));

        List<MerchantProductDTO> result = service.reorder(List.of(c.getId(), a.getId(), b.getId()));

        assertEquals(0, c.getOrderNumber());
        assertEquals(1, a.getOrderNumber());
        assertEquals(2, b.getOrderNumber());
        assertEquals(List.of("C", "A", "B"), result.stream().map(MerchantProductDTO::name).toList());
        assertEquals(List.of(0, 1, 2), result.stream().map(MerchantProductDTO::orderNumber).toList());
        verify(productRepository).flush();
    }

    @Test
    void reorderRequiresExactlyAllOwnProducts() {
        Product a = product(shop, "A", 0);
        Product b = product(shop, "B", 1);
        when(productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId())).thenReturn(List.of(a, b));
        UUID foreign = UUID.randomUUID();

        for (List<UUID> bad : List.of(
                List.of(a.getId()),                         // пропущен товар
                List.of(a.getId(), a.getId()),              // повтор
                List.of(a.getId(), foreign),                // чужой товар
                List.of(a.getId(), b.getId(), foreign))) {  // лишний
            assertStatus(HttpStatus.BAD_REQUEST, ProductServiceImpl.CATALOG_ORDER_MISMATCH, () -> service.reorder(bad));
        }
        assertStatus(HttpStatus.BAD_REQUEST, ProductServiceImpl.CATALOG_ORDER_MISMATCH,
                () -> service.reorder(Arrays.asList(a.getId(), null)));
        assertStatus(HttpStatus.BAD_REQUEST, ProductServiceImpl.CATALOG_ORDER_MISMATCH, () -> service.reorder(null));
        assertEquals(0, a.getOrderNumber());
        assertEquals(1, b.getOrderNumber());
        verify(productRepository, never()).flush();
    }

    @Test
    void reorderOfEmptyCatalogIsEmpty() {
        when(productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId())).thenReturn(List.of());
        assertEquals(List.of(), service.reorder(List.of()));
    }

    // ---------- витрина ----------

    @Test
    void storefrontShowsActiveProductsOfActiveShop() {
        Product product = product(shop, "Свеча", 0);
        product.setPriceKopecks(150_000L);
        product.setCrossedPriceKopecks(200_000L);
        when(shopLookup.activeBySlug("candles")).thenReturn(shop);
        when(productRepository.findAllByShopIdAndActiveTrueOrderByOrderNumberAscCreatedAtAsc(shop.getId()))
                .thenReturn(List.of(product));

        List<PublicProductDTO> list = service.getStorefrontProducts("candles");

        assertEquals(1, list.size());
        assertEquals(25, list.get(0).discountPercent());
        verifyNoInteractions(currentMerchant);
    }

    @Test
    void storefrontOfDisabledShopIsNotFound() {
        when(shopLookup.activeBySlug("closed")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден"));

        assertStatus(HttpStatus.NOT_FOUND, "Магазин не найден", () -> service.getStorefrontProducts("closed"));
        assertStatus(HttpStatus.NOT_FOUND, "Магазин не найден",
                () -> service.getStorefrontProduct("closed", UUID.randomUUID().toString()));
        verify(productRepository, never()).findAllByShopIdAndActiveTrueOrderByOrderNumberAscCreatedAtAsc(any());
    }

    @Test
    void storefrontProductMustBeActiveAndOfThisShop() {
        Product product = product(shop, "Свеча", 0);
        when(shopLookup.activeBySlug("candles")).thenReturn(shop);
        when(productRepository.findByIdAndShopId(product.getId(), shop.getId())).thenReturn(Optional.of(product));

        assertEquals("Свеча", service.getStorefrontProduct("candles", product.getId().toString()).name());

        product.setActive(false);
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND,
                () -> service.getStorefrontProduct("candles", product.getId().toString()));

        UUID otherShopProduct = UUID.randomUUID();
        when(productRepository.findByIdAndShopId(otherShopProduct, shop.getId())).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND,
                () -> service.getStorefrontProduct("candles", otherShopProduct.toString()));
    }

    @Test
    void malformedStorefrontProductIdIsNotFound() {
        when(shopLookup.activeBySlug("candles")).thenReturn(shop);

        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.getStorefrontProduct("candles", "abc"));
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.getStorefrontProduct("candles", " "));
        verify(productRepository, never()).findByIdAndShopId(any(), any());
    }
}
