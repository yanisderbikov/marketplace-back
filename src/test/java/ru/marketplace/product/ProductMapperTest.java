package ru.marketplace.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.MerchantPhotoDTO;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductVariantDTO;
import ru.marketplace.product.dto.PublicProductDTO;
import ru.marketplace.storage.S3FileStorage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductMapperTest {

    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private final ProductMapper mapper = new ProductMapper(s3FileStorage);
    private Product product;

    @BeforeEach
    void setUp() {
        when(s3FileStorage.publicUrl(anyString())).thenAnswer(inv -> "https://s3.test/" + inv.getArgument(0));
        Shop shop = ProductFixtures.shop("candles");
        product = ProductFixtures.product(shop, "Свеча", 3);
    }

    @Test
    void discountPercentIsFlooredAndOnlyForHigherCrossedPrice() {
        assertNull(ProductMapper.discountPercent(149_000L, null));
        assertNull(ProductMapper.discountPercent(149_000L, 149_000L));
        assertNull(ProductMapper.discountPercent(149_000L, 100_000L));
        assertEquals(25, ProductMapper.discountPercent(7_500L, 10_000L));
        // 33,33% → 33; 66,67% → 66: округление только вниз
        assertEquals(33, ProductMapper.discountPercent(6_667L, 10_000L));
        assertEquals(66, ProductMapper.discountPercent(100L, 300L));
        assertEquals(0, ProductMapper.discountPercent(9_999L, 10_000L));
        // Верхняя граница валидации цены не переполняет long
        assertEquals(99, ProductMapper.discountPercent(100L, 10_000_000_000L));
    }

    @Test
    void merchantDtoOrdersPhotosAndVariantsByPositionNotByListOrder() {
        ProductPhoto second = ProductFixtures.addPhoto(product, 1);
        ProductPhoto first = ProductFixtures.addPhoto(product, 0);
        ProductFixtures.addVariant(product, "20 см", 249_000L, 1);
        ProductFixtures.addVariant(product, "10 см", 199_000L, 0);
        product.setCrossedPriceKopecks(199_000L);
        product.setWeightGrams(300);

        MerchantProductDTO dto = mapper.toMerchant(product);

        assertEquals(List.of(
                new MerchantPhotoDTO(first.getId(), "https://s3.test/" + first.getS3Key()),
                new MerchantPhotoDTO(second.getId(), "https://s3.test/" + second.getS3Key())), dto.photos());
        assertEquals(List.of("10 см", "20 см"), dto.variants().stream().map(ProductVariantDTO::label).toList());
        assertEquals(199_000L, dto.variants().get(0).priceKopecks());
        assertEquals(product.getId(), dto.id());
        assertEquals(149_000L, dto.priceKopecks());
        assertEquals(199_000L, dto.crossedPriceKopecks());
        assertTrue(dto.active());
        assertFalse(dto.preorder());
        assertEquals(3, dto.orderNumber());
        assertEquals(300, dto.weightGrams());
        assertNull(dto.lengthCm());
        assertEquals(ProductFixtures.CREATED, dto.createdAt());
        assertEquals(ProductFixtures.CREATED, dto.updatedAt());
    }

    @Test
    void publicDtoHasPhotoUrlsAndDiscount() {
        ProductPhoto main = ProductFixtures.addPhoto(product, 0);
        product.setPriceKopecks(150_000L);
        product.setCrossedPriceKopecks(200_000L);
        product.setPreorder(true);

        PublicProductDTO dto = mapper.toPublic(product);

        assertEquals(List.of("https://s3.test/" + main.getS3Key()), dto.photos());
        assertEquals(25, dto.discountPercent());
        assertTrue(dto.preorder());
        assertEquals(List.of(), dto.variants());
        assertEquals("Свеча", dto.name());
    }

    @Test
    void publicDtoWithoutCrossedPriceHasNoDiscount() {
        assertNull(mapper.toPublic(product).discountPercent());
        assertEquals(List.of(), mapper.toPublic(product).photos());
    }
}
