package ru.marketplace.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.MutableClock;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.MerchantPhotoDTO;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.storage.PresignedUpload;
import ru.marketplace.storage.S3FileStorage;
import ru.marketplace.storage.UploadRateLimiter;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static ru.marketplace.product.ProductFixtures.*;

class ProductPhotoServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductLockRepository productLockRepository = mock(ProductLockRepository.class);
    private final S3FileStorage s3FileStorage = mock(S3FileStorage.class);
    private Shop shop;
    private Product product;
    private String folder;
    private ProductPhotoService service;

    @BeforeEach
    void setUp() {
        shop = shop("candles");
        product = product(shop, "Свеча", 0);
        folder = "shops/" + shop.getId() + "/products/" + product.getId();
        when(currentMerchant.shop()).thenReturn(shop);
        when(s3FileStorage.publicUrl(anyString())).thenAnswer(inv -> "https://s3.test/" + inv.getArgument(0));
        when(productRepository.findByIdAndShopId(product.getId(), shop.getId())).thenReturn(Optional.of(product));
        when(productLockRepository.findOwnForUpdate(any(), any())).thenReturn(Optional.empty());
        when(productLockRepository.findOwnForUpdate(product.getId(), shop.getId())).thenReturn(Optional.of(product));
        service = new ProductPhotoService(currentMerchant, productRepository, productLockRepository, s3FileStorage,
                new ProductMapper(s3FileStorage), new UploadRateLimiter(new MutableClock(NOW)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void assertStatus(HttpStatus status, String reason, Executable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call);
        assertEquals(status, e.getStatusCode());
        if (reason != null) {
            assertEquals(reason, e.getReason());
        }
    }

    private void fillPhotos(int count) {
        for (int i = 0; i < count; i++) {
            addPhoto(product, i);
        }
    }

    @Test
    void keyPrefixIsProductFolderOfShop() {
        assertEquals(folder, ProductPhotoService.keyPrefix(shop.getId(), product.getId()));
    }

    // ---------- presign ----------

    @Test
    void presignSignsUploadIntoProductFolder() {
        when(s3FileStorage.presignUpload(folder, "photo.JPG", "image/jpeg", 2048L))
                .thenReturn(new PresignedUpload("https://upload", folder + "/u.jpg"));

        PresignUploadResponseDTO response = service.presign(product.getId(), "photo.JPG", "image/jpeg", 2048L);

        assertEquals(new PresignUploadResponseDTO("https://upload", folder + "/u.jpg"), response);
    }

    @Test
    void presignIsLimitedPerShopEvenWithoutRegisteredPhotos() {
        when(s3FileStorage.presignUpload(any(), any(), any(), any()))
                .thenReturn(new PresignedUpload("https://upload", folder + "/u.jpg"));
        // Неприбитые загрузки число фото не увеличивают — без лимита подписи можно было бы брать бесконечно
        for (int i = 0; i < 300; i++) {
            service.presign(product.getId(), "a.jpg", "image/jpeg", null);
        }

        assertStatus(HttpStatus.TOO_MANY_REQUESTS, "Слишком много загрузок за час. Попробуйте позже",
                () -> service.presign(product.getId(), "a.jpg", "image/jpeg", null));
        verify(s3FileStorage, times(300)).presignUpload(any(), any(), any(), any());
    }

    @Test
    void presignForForeignProductIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(productRepository.findByIdAndShopId(foreign, shop.getId())).thenReturn(Optional.empty());

        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.presign(foreign, "a.jpg", "image/jpeg", 1L));
        verify(s3FileStorage, never()).presignUpload(any(), any(), any(), any());
    }

    @Test
    void presignRefusesWhenCardIsFull() {
        fillPhotos(ProductPhotoService.MAX_PHOTOS);

        assertStatus(HttpStatus.BAD_REQUEST, ProductPhotoService.PHOTO_LIMIT, () -> service.presign(product.getId(), "a.jpg", "image/jpeg", 1L));
        verify(s3FileStorage, never()).presignUpload(any(), any(), any(), any());
    }

    // ---------- register ----------

    @Test
    void registerAppendsPhotoAtTheEnd() {
        ProductPhoto first = addPhoto(product, 0);
        ProductPhoto second = addPhoto(product, 3);
        String key = folder + "/new.png";

        MerchantProductDTO dto = service.register(product.getId(), "  " + key + " ");

        assertEquals(3, product.getPhotos().size());
        ProductPhoto added = product.getPhotos().get(2);
        assertEquals(key, added.getS3Key());
        assertEquals(4, added.getPosition(), "после максимальной позиции");
        assertSame(product, added.getProduct());
        assertEquals(NOW, added.getCreatedAt());
        assertEquals(NOW, product.getUpdatedAt());
        verify(productRepository).flush();
        verify(productRepository, never()).save(any());
        assertEquals(Arrays.asList(first.getId(), second.getId(), null), dto.photos().stream().map(MerchantPhotoDTO::id).toList());
        assertEquals("https://s3.test/" + key, dto.photos().get(2).url());
        verify(s3FileStorage).requireUploaded(key);
    }

    @Test
    void registerRejectsMissingOrOversizedUpload() {
        String key = folder + "/huge.jpg";
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, S3FileStorage.TOO_LARGE))
                .when(s3FileStorage).requireUploaded(key);

        assertStatus(HttpStatus.BAD_REQUEST, S3FileStorage.TOO_LARGE, () -> service.register(product.getId(), key));
        assertTrue(product.getPhotos().isEmpty());
        verify(productRepository, never()).flush();
    }

    @Test
    void firstPhotoGetsPositionZero() {
        service.register(product.getId(), folder + "/a.jpg");
        assertEquals(0, product.getPhotos().get(0).getPosition());
    }

    @Test
    void registerRejectsKeysOutsideProductFolder() {
        Product other = product(shop, "Другой", 1);
        Shop otherShop = shop("other");
        for (String key : List.of(
                ProductPhotoService.keyPrefix(shop.getId(), other.getId()) + "/a.jpg",          // другой товар
                ProductPhotoService.keyPrefix(otherShop.getId(), product.getId()) + "/a.jpg",   // другой магазин
                "shops/" + shop.getId() + "/branding/logo/a.jpg",
                folder,
                folder + "/",
                folder + "/../x.jpg",
                folder + "//x.jpg",
                folder + "-evil/x.jpg")) {
            assertStatus(HttpStatus.BAD_REQUEST, "Некорректный ключ файла", () -> service.register(product.getId(), key));
        }
        assertStatus(HttpStatus.BAD_REQUEST, "Некорректный ключ файла",
                () -> service.register(product.getId(), folder + "/" + "a".repeat(600) + ".jpg"));
        assertTrue(product.getPhotos().isEmpty());
        verify(productRepository, never()).flush();
        verify(s3FileStorage, never()).delete(any());
    }

    @Test
    void registerForForeignProductIsNotFound() {
        UUID foreign = UUID.randomUUID();
        String key = ProductPhotoService.keyPrefix(shop.getId(), foreign) + "/a.jpg";

        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.register(foreign, key));
    }

    @Test
    void registeringSameKeyTwiceIsNoop() {
        ProductPhoto existing = addPhoto(product, 0);

        MerchantProductDTO dto = service.register(product.getId(), existing.getS3Key());

        assertEquals(1, product.getPhotos().size());
        assertEquals(1, dto.photos().size());
        verify(productRepository, never()).flush();
    }

    @Test
    void registerOverLimitIsRejectedAndUploadedFileRemoved() {
        fillPhotos(ProductPhotoService.MAX_PHOTOS);
        String key = folder + "/extra.jpg";

        assertStatus(HttpStatus.BAD_REQUEST, ProductPhotoService.PHOTO_LIMIT, () -> service.register(product.getId(), key));

        assertEquals(ProductPhotoService.MAX_PHOTOS, product.getPhotos().size());
        verify(s3FileStorage).delete(key);
        verify(s3FileStorage, never()).requireUploaded(any());
        verify(productRepository, never()).flush();
    }

    @Test
    void nineteenPhotosStillAcceptOne() {
        fillPhotos(ProductPhotoService.MAX_PHOTOS - 1);
        service.register(product.getId(), folder + "/last.jpg");
        assertEquals(ProductPhotoService.MAX_PHOTOS, product.getPhotos().size());
    }

    // ---------- delete ----------

    @Test
    void deleteRemovesPhotoRenumbersRestAndDeletesObject() {
        ProductPhoto a = addPhoto(product, 0);
        ProductPhoto b = addPhoto(product, 1);
        ProductPhoto c = addPhoto(product, 2);

        MerchantProductDTO dto = service.delete(product.getId(), b.getId());

        assertEquals(List.of(a, c), product.getPhotos());
        assertEquals(0, a.getPosition());
        assertEquals(1, c.getPosition());
        assertEquals(List.of(a.getId(), c.getId()), dto.photos().stream().map(MerchantPhotoDTO::id).toList());
        assertEquals(NOW, product.getUpdatedAt());
        verify(productRepository).flush();
        verify(s3FileStorage).delete(b.getS3Key());
        verify(s3FileStorage, never()).delete(a.getS3Key());
    }

    @Test
    void deleteUnknownPhotoIsNotFound() {
        addPhoto(product, 0);

        assertStatus(HttpStatus.NOT_FOUND, ProductPhotoService.PHOTO_NOT_FOUND, () -> service.delete(product.getId(), UUID.randomUUID()));
        assertEquals(1, product.getPhotos().size());
        verify(s3FileStorage, never()).delete(any());
    }

    @Test
    void photoOfForeignProductCannotBeDeleted() {
        UUID foreign = UUID.randomUUID();
        assertStatus(HttpStatus.NOT_FOUND, ProductServiceImpl.NOT_FOUND, () -> service.delete(foreign, UUID.randomUUID()));
        verify(s3FileStorage, never()).delete(any());
    }

    // ---------- reorder ----------

    @Test
    void reorderMakesFirstPhotoMain() {
        ProductPhoto a = addPhoto(product, 0);
        ProductPhoto b = addPhoto(product, 1);
        ProductPhoto c = addPhoto(product, 2);

        MerchantProductDTO dto = service.reorder(product.getId(), List.of(c.getId(), a.getId(), b.getId()));

        assertEquals(List.of(c.getId(), a.getId(), b.getId()), dto.photos().stream().map(MerchantPhotoDTO::id).toList());
        assertEquals(0, c.getPosition());
        assertEquals(1, a.getPosition());
        assertEquals(2, b.getPosition());
        verify(productRepository).flush();
    }

    @Test
    void reorderRequiresExactlyAllPhotosOfProduct() {
        ProductPhoto a = addPhoto(product, 0);
        ProductPhoto b = addPhoto(product, 1);

        for (List<UUID> bad : List.of(
                List.of(a.getId()),
                List.of(a.getId(), a.getId()),
                List.of(a.getId(), UUID.randomUUID()),
                List.of(a.getId(), b.getId(), UUID.randomUUID()))) {
            assertStatus(HttpStatus.BAD_REQUEST, ProductPhotoService.PHOTO_ORDER_MISMATCH, () -> service.reorder(product.getId(), bad));
        }
        assertStatus(HttpStatus.BAD_REQUEST, ProductPhotoService.PHOTO_ORDER_MISMATCH,
                () -> service.reorder(product.getId(), Arrays.asList(a.getId(), null)));
        assertEquals(0, a.getPosition());
        assertEquals(1, b.getPosition());
        verify(productRepository, never()).flush();
    }

    // ---------- удаление объектов после коммита ----------

    @Test
    void objectsAreDeletedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.deleteObjectsAfterCommit(Arrays.asList("k1", null, " ", "k2", "k1"));

        verify(s3FileStorage, never()).delete(any());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(s3FileStorage).delete("k1");
        verify(s3FileStorage).delete("k2");
        verify(s3FileStorage, times(2)).delete(any());
    }

    @Test
    void rolledBackDeleteKeepsObjects() {
        TransactionSynchronizationManager.initSynchronization();
        ProductPhoto photo = addPhoto(product, 0);

        service.delete(product.getId(), photo.getId());
        // Откат: afterCommit не вызывается
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(s3FileStorage, never()).delete(any());
    }
}
