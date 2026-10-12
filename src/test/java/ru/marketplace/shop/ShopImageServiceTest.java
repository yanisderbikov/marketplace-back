package ru.marketplace.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Shop;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.storage.PresignedUpload;
import ru.marketplace.storage.S3FileStorage;
import ru.marketplace.storage.UploadRateLimiter;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShopImageServiceTest {

    private static final UUID SHOP_ID = UUID.fromString("0b6f2c1e-1111-4a2b-9c3d-123456789abc");
    private static final String LOGO_PREFIX = "shops/" + SHOP_ID + "/branding/logo";
    private static final String HERO_PREFIX = "shops/" + SHOP_ID + "/branding/hero";

    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private ShopImageService service;
    private Shop shop;

    @BeforeEach
    void setUp() {
        service = new ShopImageService(currentMerchant, shopRepository, s3FileStorage, new ShopMapper(s3FileStorage),
                new UploadRateLimiter(new MutableClock(Instant.parse("2026-10-01T09:00:00Z"))));
        shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setSlug("candles");
        shop.setName("Свечи");
        shop.setActive(true);
        shop.setBrandColor("#111111");
        shop.setTinkoffReceiptEnabled(false);
        when(currentMerchant.shop()).thenReturn(shop);
        when(shopRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3FileStorage.publicUrl(anyString())).thenAnswer(inv -> "https://s3.example/" + inv.getArgument(0));
    }

    private static HttpStatus statusOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void presignSignsUploadIntoShopBrandingFolder() {
        when(s3FileStorage.presignUpload(LOGO_PREFIX, "logo.png", "image/png", 4096L))
                .thenReturn(new PresignedUpload("https://s3.example/put", LOGO_PREFIX + "/abc.png"));

        PresignUploadResponseDTO response = service.presign("logo", "logo.png", "image/png", 4096L);

        assertEquals("https://s3.example/put", response.uploadUrl());
        assertEquals(LOGO_PREFIX + "/abc.png", response.key());
    }

    @Test
    void heroUsesItsOwnFolder() {
        when(s3FileStorage.presignUpload(HERO_PREFIX, "hero.jpg", "image/jpeg", null))
                .thenReturn(new PresignedUpload("https://s3.example/put", HERO_PREFIX + "/abc.jpg"));

        assertEquals(HERO_PREFIX + "/abc.jpg", service.presign("hero", "hero.jpg", "image/jpeg", null).key());
    }

    @Test
    void presignIsLimitedPerShop() {
        when(s3FileStorage.presignUpload(any(), any(), any(), any()))
                .thenReturn(new PresignedUpload("https://s3.example/put", LOGO_PREFIX + "/abc.png"));
        for (int i = 0; i < 300; i++) {
            service.presign("logo", "logo.png", "image/png", null);
        }

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.presign("hero", "hero.png", "image/png", null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        verify(s3FileStorage, times(300)).presignUpload(any(), any(), any(), any());
    }

    @Test
    void unknownKindIsBadRequestBeforeAnyWork() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.presign("banner", "a.png", "image/png", 1L)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("avatar", LOGO_PREFIX + "/a.png")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.delete("")));
        verifyNoInteractions(currentMerchant);
    }

    @Test
    void registerStoresKeyAndDeletesPreviousObject() {
        shop.setLogoKey(LOGO_PREFIX + "/old.png");

        MerchantShopDTO dto = service.register("logo", " " + LOGO_PREFIX + "/new.png ");

        assertEquals(LOGO_PREFIX + "/new.png", shop.getLogoKey());
        assertEquals("https://s3.example/" + LOGO_PREFIX + "/new.png", dto.logoUrl());
        assertNull(dto.heroImageUrl());
        verify(s3FileStorage).requireUploaded(LOGO_PREFIX + "/new.png");
        verify(s3FileStorage).delete(LOGO_PREFIX + "/old.png");
    }

    @Test
    void registerRejectsMissingOrOversizedUploadAndKeepsPreviousLogo() {
        shop.setLogoKey(LOGO_PREFIX + "/old.png");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, S3FileStorage.TOO_LARGE))
                .when(s3FileStorage).requireUploaded(LOGO_PREFIX + "/huge.png");

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("logo", LOGO_PREFIX + "/huge.png")));

        assertEquals(LOGO_PREFIX + "/old.png", shop.getLogoKey());
        verify(shopRepository, never()).save(any());
        verify(s3FileStorage, never()).delete(LOGO_PREFIX + "/old.png");
    }

    @Test
    void registerRejectsKeysOutsideExpectedFolder() {
        String otherShop = "shops/" + UUID.randomUUID() + "/branding/logo/x.png";
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("logo", otherShop)));
        // Фото витрины нельзя подсунуть как логотип и наоборот
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("logo", HERO_PREFIX + "/x.png")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("logo", LOGO_PREFIX + "/../../x.png")));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.register("logo", LOGO_PREFIX)));
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> service.register("logo", LOGO_PREFIX + "/" + "a".repeat(600) + ".png")));

        assertNull(shop.getLogoKey());
        verify(shopRepository, never()).save(any());
        verify(s3FileStorage, never()).delete(anyString());
    }

    @Test
    void registerSameKeyAgainDoesNotDeleteIt() {
        shop.setHeroImageKey(HERO_PREFIX + "/same.jpg");

        service.register("hero", HERO_PREFIX + "/same.jpg");

        assertEquals(HERO_PREFIX + "/same.jpg", shop.getHeroImageKey());
        verify(s3FileStorage, never()).delete(anyString());
    }

    @Test
    void deleteClearsKeyAndRemovesObject() {
        shop.setHeroImageKey(HERO_PREFIX + "/hero.jpg");
        shop.setLogoKey(LOGO_PREFIX + "/logo.png");

        MerchantShopDTO dto = service.delete("hero");

        assertNull(shop.getHeroImageKey());
        assertNull(dto.heroImageUrl());
        assertEquals(LOGO_PREFIX + "/logo.png", shop.getLogoKey());
        verify(s3FileStorage).delete(HERO_PREFIX + "/hero.jpg");
    }

    @Test
    void deleteWithoutImageIsNoop() {
        service.delete("logo");

        verify(shopRepository, never()).save(any());
        verify(s3FileStorage, never()).delete(anyString());
    }
}
