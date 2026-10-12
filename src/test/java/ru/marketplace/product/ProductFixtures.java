package ru.marketplace.product;

import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.ProductVariant;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.product.dto.ProductVariantRequestDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Сущности и запросы для тестов каталога: создаются так же, как в коде, — new + сеттеры, без дефолтов. */
final class ProductFixtures {

    static final Instant CREATED = Instant.parse("2026-09-01T10:00:00Z");

    private ProductFixtures() {
    }

    static Shop shop(String slug) {
        return Shop.builder()
                .id(UUID.randomUUID())
                .slug(slug)
                .name("Магазин " + slug)
                .active(true)
                .brandColor("#7f95a7")
                .tinkoffReceiptEnabled(false)
                .build();
    }

    static Product product(Shop shop, String name, int orderNumber) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setShop(shop);
        product.setName(name);
        product.setDescription("Описание " + name);
        product.setPriceKopecks(149_000L);
        product.setCrossedPriceKopecks(null);
        product.setActive(true);
        product.setPreorder(false);
        product.setOrderNumber(orderNumber);
        product.setCreatedAt(CREATED);
        product.setUpdatedAt(CREATED);
        return product;
    }

    static ProductPhoto addPhoto(Product product, int position) {
        UUID id = UUID.randomUUID();
        ProductPhoto photo = ProductPhoto.builder()
                .id(id)
                .product(product)
                .s3Key(PhotoKeys.under(product) + "/" + id + ".jpg")
                .position(position)
                .createdAt(CREATED)
                .build();
        product.getPhotos().add(photo);
        return photo;
    }

    static ProductVariant addVariant(Product product, String label, long priceKopecks, int orderNumber) {
        ProductVariant variant = ProductVariant.builder()
                .id(UUID.randomUUID())
                .product(product)
                .label(label)
                .priceKopecks(priceKopecks)
                .orderNumber(orderNumber)
                .build();
        product.getVariants().add(variant);
        return variant;
    }

    static ProductSaveRequestDTO request(String name) {
        return ProductSaveRequestDTO.builder()
                .name(name)
                .description("")
                .priceKopecks(149_000L)
                .crossedPriceKopecks(null)
                .active(true)
                .preorder(false)
                .variants(new ArrayList<>())
                .build();
    }

    static ProductVariantRequestDTO variant(UUID id, String label, long priceKopecks) {
        return new ProductVariantRequestDTO(id, label, priceKopecks);
    }

    static List<String> labels(Product product) {
        return product.getVariants().stream().map(ProductVariant::getLabel).toList();
    }

    /** Папка товара в бакете, как её строит ProductPhotoService. */
    static final class PhotoKeys {
        private PhotoKeys() {
        }

        static String under(Product product) {
            return ProductPhotoService.keyPrefix(product.getShop().getId(), product.getId());
        }
    }
}
