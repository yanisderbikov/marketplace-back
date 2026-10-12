package ru.marketplace.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.storage.PresignedUpload;
import ru.marketplace.storage.S3FileStorage;
import ru.marketplace.storage.UploadRateLimiter;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Фото товаров. Как в anyforms, файл идёт из браузера сразу в S3 по presigned PUT — бэкенд только подписывает URL.
 * Но вместо листинга папки бакета и строки photo_order каждое фото — строка product_photo: после загрузки фронт
 * присылает ключ, сервер проверяет, что ключ лежит в папке именно этого товара этого магазина
 * (shops/{shopId}/products/{productId}/), и только тогда привязывает. Иначе мерчант мог бы показать на своей
 * витрине чужой файл из бакета.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPhotoService {

    public static final int MAX_PHOTOS = 20;
    static final String PHOTO_LIMIT = "У товара может быть не больше " + MAX_PHOTOS + " фото";
    static final String PHOTO_NOT_FOUND = "Фото не найдено";
    static final String PHOTO_ORDER_MISMATCH = "Список фото устарел — обновите страницу";
    /** Ключ лежит в колонке VARCHAR(512) */
    private static final int KEY_MAX = 512;

    private final CurrentMerchant currentMerchant;
    private final ProductRepository productRepository;
    private final ProductLockRepository productLockRepository;
    private final S3FileStorage s3FileStorage;
    private final ProductMapper productMapper;
    private final UploadRateLimiter uploadRateLimiter;
    private final Clock clock;

    /** Папка товара в бакете; её же проверяет регистрация ключа. */
    public static String keyPrefix(UUID shopId, UUID productId) {
        return "shops/" + shopId + "/products/" + productId;
    }

    /**
     * Presigned PUT для одного фото. Лимит проверяем уже здесь: при полной карточке файл даже не уйдёт в бакет
     * (окончательная проверка — при регистрации, под блокировкой товара). Число фото считает только привязанные,
     * поэтому подписи сверх него ограничивает ещё и лимит загрузок магазина в час.
     */
    @Transactional(readOnly = true)
    public PresignUploadResponseDTO presign(UUID productId, String filename, String contentType, Long size) {
        Shop shop = currentMerchant.shop();
        Product product = productRepository.findByIdAndShopId(productId, shop.getId())
                .orElseThrow(ProductServiceImpl::productNotFound);
        if (product.getPhotos().size() >= MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PHOTO_LIMIT);
        }
        uploadRateLimiter.acquire(shop.getId());
        PresignedUpload upload = s3FileStorage.presignUpload(keyPrefix(shop.getId(), product.getId()), filename,
                contentType, size);
        return PresignUploadResponseDTO.of(upload);
    }

    /** Привязать загруженный файл к товару — фото встаёт в конец. Повтор того же ключа ничего не меняет. */
    @Transactional
    public MerchantProductDTO register(UUID productId, String key) {
        Shop shop = currentMerchant.shop();
        Product product = lockOwn(shop, productId);
        String checkedKey = S3FileStorage.requireKeyUnder(key, keyPrefix(shop.getId(), product.getId()));
        if (checkedKey.length() > KEY_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ключ файла");
        }
        List<ProductPhoto> photos = product.getPhotos();
        if (photos.stream().anyMatch(photo -> checkedKey.equals(photo.getS3Key()))) {
            // Ретрай сети после успешной привязки: второй строки с тем же файлом не нужно
            return productMapper.toMerchant(product);
        }
        if (photos.size() >= MAX_PHOTOS) {
            // Файл уже в папке товара, но ни на что не ссылается — убираем, чтобы не копить сирот в бакете
            s3FileStorage.delete(checkedKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PHOTO_LIMIT);
        }
        // Файл реально загружен и не больше лимита: подпись без длины (старый фронт) размер не ограничивает
        s3FileStorage.requireUploaded(checkedKey);
        int position = photos.stream()
                .map(ProductPhoto::getPosition)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
        photos.add(ProductPhoto.builder()
                .product(product)
                .s3Key(checkedKey)
                .position(position)
                .createdAt(Instant.now(clock))
                .build());
        touch(product);
        // flush, а не save: товар уже managed, а merge управляемой сущности с новым фото в коллекции
        // может вставить фото дважды. После flush у фото есть id для ответа.
        productRepository.flush();
        return productMapper.toMerchant(product);
    }

    /** Удалить фото; объект в S3 удаляется после коммита. Оставшиеся фото перенумеровываются с 0. */
    @Transactional
    public MerchantProductDTO delete(UUID productId, UUID photoId) {
        Shop shop = currentMerchant.shop();
        Product product = lockOwn(shop, productId);
        ProductPhoto photo = product.getPhotos().stream()
                .filter(p -> p.getId() != null && p.getId().equals(photoId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PHOTO_NOT_FOUND));
        product.getPhotos().remove(photo);
        List<ProductPhoto> rest = product.getPhotos().stream()
                .sorted(Comparator.comparing(ProductPhoto::getPosition, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (int i = 0; i < rest.size(); i++) {
            rest.get(i).setPosition(i);
        }
        touch(product);
        productRepository.flush();
        deleteObjectsAfterCommit(List.of(photo.getS3Key()));
        return productMapper.toMerchant(product);
    }

    /**
     * Порядок фото на витрине; первое — главное. Передать нужно ровно все фото товара: если набор не совпал,
     * у фронта устаревшая карточка (фото добавили/удалили в другой вкладке) — лучше 400, чем молча потерять порядок.
     */
    @Transactional
    public MerchantProductDTO reorder(UUID productId, List<UUID> photoIds) {
        Shop shop = currentMerchant.shop();
        Product product = lockOwn(shop, productId);
        Map<UUID, ProductPhoto> byId = product.getPhotos().stream()
                .collect(Collectors.toMap(ProductPhoto::getId, Function.identity()));
        if (!isSameSet(photoIds, byId.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PHOTO_ORDER_MISMATCH);
        }
        for (int i = 0; i < photoIds.size(); i++) {
            byId.get(photoIds.get(i)).setPosition(i);
        }
        touch(product);
        productRepository.flush();
        return productMapper.toMerchant(product);
    }

    /**
     * Удалить объекты из S3 только после коммита: если транзакция откатится, строки product_photo останутся
     * и будут ссылаться на эти ключи — файлы должны остаться. Вне транзакции (тесты) — сразу.
     * Ошибки S3 S3FileStorage только логирует: осиротевший файл дешевле упавшего удаления.
     */
    public void deleteObjectsAfterCommit(Collection<String> keys) {
        List<String> toDelete = keys == null ? List.of() : keys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (toDelete.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    toDelete.forEach(s3FileStorage::delete);
                }
            });
        } else {
            toDelete.forEach(s3FileStorage::delete);
        }
    }

    /** Товар своего магазина под блокировкой строки (см. ProductLockRepository); чужой выглядит как несуществующий — 404. */
    private Product lockOwn(Shop shop, UUID productId) {
        return productLockRepository.findOwnForUpdate(productId, shop.getId())
                .orElseThrow(ProductServiceImpl::productNotFound);
    }

    /**
     * Фото — отдельная таблица, их изменение не трогает строку product, и updated_at не сдвинулся бы.
     * Меняем поле — строка становится «грязной», и @PreUpdate проставит время при flush.
     */
    private void touch(Product product) {
        product.setUpdatedAt(Instant.now(clock));
    }

    /** Ровно тот же набор id: без null, без повторов, ничего лишнего и ничего не пропущено. */
    static boolean isSameSet(List<UUID> requested, Collection<UUID> actual) {
        if (requested == null || requested.size() != actual.size()) {
            return false;
        }
        HashSet<UUID> unique = new HashSet<>(requested);
        return unique.size() == requested.size() && !unique.contains(null) && unique.containsAll(actual);
    }
}
