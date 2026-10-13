package ru.marketplace.shop;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.marketplace.model.Shop;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.shop.dto.PublicShopCardDTO;
import ru.marketplace.shop.dto.PublicShopDTO;
import ru.marketplace.storage.S3FileStorage;

import java.util.List;

/**
 * Shop → DTO. В БД лежат только ключи картинок: бакет приватный, поэтому URL логотипа и фото витрины —
 * presigned GET, который подписывается при каждой выдаче (S3FileStorage держит его стабильным несколько часов).
 */
@Component
@RequiredArgsConstructor
public class ShopMapper {

    public static final String STOREFRONT_PATH_PREFIX = "/shop/";

    private final S3FileStorage s3FileStorage;

    public MerchantShopDTO toMerchant(Shop shop) {
        List<String> missing = ShopReadiness.missing(shop);
        return MerchantShopDTO.builder()
                .id(shop.getId())
                .slug(shop.getSlug())
                .name(shop.getName())
                .active(Boolean.TRUE.equals(shop.getActive()))
                .storefrontPath(STOREFRONT_PATH_PREFIX + shop.getSlug())
                .customDomain(shop.getCustomDomain())
                .brandColor(shop.getBrandColor())
                .logoUrl(s3FileStorage.publicUrl(shop.getLogoKey()))
                .heroImageUrl(s3FileStorage.publicUrl(shop.getHeroImageKey()))
                .tagline(shop.getTagline())
                .description(shop.getDescription())
                .about(shop.getAbout())
                .supportTelegram(shop.getSupportTelegram())
                .contactEmail(shop.getContactEmail())
                .contactPhone(shop.getContactPhone())
                .sellerName(shop.getSellerName())
                .sellerInn(shop.getSellerInn())
                .sellerOgrn(shop.getSellerOgrn())
                .sellerAddress(shop.getSellerAddress())
                .cdekFromPvzCode(shop.getCdekFromPvzCode())
                .cdekFromCity(shop.getCdekFromCity())
                .cdekFromAddress(shop.getCdekFromAddress())
                .cdekClientId(shop.getCdekClientId())
                // Как и пароль терминала: сам секрет (даже шифртекст) наружу не уходит
                .cdekSecretSet(shop.getCdekClientSecretEnc() != null && !shop.getCdekClientSecretEnc().isBlank())
                .paymentProvider(ShopPayment.activeProvider(shop))
                .tinkoffTerminalKey(shop.getTinkoffTerminalKey())
                // Сам пароль (даже шифртекст) наружу не уходит — фронту достаточно знать, что он есть
                .tinkoffPasswordSet(shop.getTinkoffPasswordEnc() != null && !shop.getTinkoffPasswordEnc().isBlank())
                .tinkoffReceiptEnabled(Boolean.TRUE.equals(shop.getTinkoffReceiptEnabled()))
                .tinkoffTaxation(shop.getTinkoffTaxation())
                .tinkoffTax(shop.getTinkoffTax())
                .yookassaShopId(shop.getYookassaShopId())
                .yookassaSecretKeySet(shop.getYookassaSecretKeyEnc() != null && !shop.getYookassaSecretKeyEnc().isBlank())
                .yookassaReceiptEnabled(Boolean.TRUE.equals(shop.getYookassaReceiptEnabled()))
                .yookassaTaxation(shop.getYookassaTaxation())
                .yookassaTax(shop.getYookassaTax())
                .acceptsOrders(Boolean.TRUE.equals(shop.getActive()) && missing.isEmpty())
                .missingForOrders(missing)
                .build();
    }

    public PublicShopDTO toPublic(Shop shop) {
        return PublicShopDTO.builder()
                .slug(shop.getSlug())
                .name(shop.getName())
                .brandColor(shop.getBrandColor())
                .logoUrl(s3FileStorage.publicUrl(shop.getLogoKey()))
                .heroImageUrl(s3FileStorage.publicUrl(shop.getHeroImageKey()))
                .tagline(shop.getTagline())
                .description(shop.getDescription())
                .about(shop.getAbout())
                .supportTelegram(shop.getSupportTelegram())
                .contactEmail(shop.getContactEmail())
                .contactPhone(shop.getContactPhone())
                .sellerName(shop.getSellerName())
                .sellerInn(shop.getSellerInn())
                .sellerOgrn(shop.getSellerOgrn())
                .sellerAddress(shop.getSellerAddress())
                .acceptsOrders(ShopReadiness.acceptsOrders(shop))
                .build();
    }

    public PublicShopCardDTO toCard(Shop shop, long productsCount) {
        return PublicShopCardDTO.builder()
                .slug(shop.getSlug())
                .name(shop.getName())
                .brandColor(shop.getBrandColor())
                .logoUrl(s3FileStorage.publicUrl(shop.getLogoKey()))
                .heroImageUrl(s3FileStorage.publicUrl(shop.getHeroImageKey()))
                .tagline(shop.getTagline())
                .productsCount(productsCount)
                .build();
    }
}
