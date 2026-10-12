package ru.marketplace.shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Shop;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.shop.dto.MerchantShopDTO;
import ru.marketplace.storage.PresignedUpload;
import ru.marketplace.storage.S3FileStorage;
import ru.marketplace.storage.UploadRateLimiter;
import ru.marketplace.storage.dto.PresignUploadResponseDTO;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Логотип и фото витрины. Файл идёт из браузера сразу в S3 по presigned PUT (как фото товаров в anyforms),
 * бэкенд только подписывает URL и потом запоминает ключ. Ключ, который прислал фронт, обязан лежать
 * в папке этого магазина и этой картинки — иначе мерчант мог бы привязать к витрине чужой файл.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopImageService {

    /** Ключ лежит в колонке VARCHAR(512) */
    private static final int KEY_MAX = 512;

    /** Какие картинки есть у витрины: путь в URL (/images/{kind}) и колонка Shop */
    enum Kind {
        LOGO("logo", Shop::getLogoKey, Shop::setLogoKey),
        HERO("hero", Shop::getHeroImageKey, Shop::setHeroImageKey);

        private final String path;
        private final Function<Shop, String> getter;
        private final BiConsumer<Shop, String> setter;

        Kind(String path, Function<Shop, String> getter, BiConsumer<Shop, String> setter) {
            this.path = path;
            this.getter = getter;
            this.setter = setter;
        }

        static Kind parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.path.equals(value)) {
                    return kind;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный тип картинки: ожидается logo или hero");
        }
    }

    private final CurrentMerchant currentMerchant;
    private final ShopRepository shopRepository;
    private final S3FileStorage s3FileStorage;
    private final ShopMapper shopMapper;
    private final UploadRateLimiter uploadRateLimiter;

    /**
     * Подписать прямую загрузку в shops/{shopId}/branding/{kind}/. 400 — не картинка, больше 10 МБ или
     * неизвестный kind; 429 — лимит загрузок магазина в час (иначе подписи можно было бы брать в цикле).
     */
    public PresignUploadResponseDTO presign(String kindPath, String filename, String contentType, Long size) {
        Kind kind = Kind.parse(kindPath);
        Shop shop = currentMerchant.shop();
        uploadRateLimiter.acquire(shop.getId());
        PresignedUpload upload = s3FileStorage.presignUpload(keyPrefix(shop, kind), filename, contentType, size);
        return PresignUploadResponseDTO.of(upload);
    }

    /** Запомнить загруженный файл; прежний объект удаляется из S3 после коммита. */
    @Transactional
    public MerchantShopDTO register(String kindPath, String key) {
        Kind kind = Kind.parse(kindPath);
        Shop shop = currentMerchant.shop();
        String checkedKey = S3FileStorage.requireKeyUnder(key, keyPrefix(shop, kind));
        if (checkedKey.length() > KEY_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ключ файла");
        }
        String previousKey = kind.getter.apply(shop);
        if (checkedKey.equals(previousKey)) {
            // Повтор того же запроса (ретрай сети) — ничего не меняем и, главное, не удаляем текущий файл
            return shopMapper.toMerchant(shop);
        }
        // Файл реально загружен и не больше лимита: подпись без длины (старый фронт) размер не ограничивает
        s3FileStorage.requireUploaded(checkedKey);
        kind.setter.accept(shop, checkedKey);
        Shop saved = shopRepository.save(shop);
        deleteAfterCommit(previousKey);
        return shopMapper.toMerchant(saved);
    }

    /** Убрать картинку с витрины; файл удаляется из S3 после коммита. */
    @Transactional
    public MerchantShopDTO delete(String kindPath) {
        Kind kind = Kind.parse(kindPath);
        Shop shop = currentMerchant.shop();
        String previousKey = kind.getter.apply(shop);
        if (previousKey == null) {
            return shopMapper.toMerchant(shop);
        }
        kind.setter.accept(shop, null);
        Shop saved = shopRepository.save(shop);
        deleteAfterCommit(previousKey);
        return shopMapper.toMerchant(saved);
    }

    static String keyPrefix(Shop shop, Kind kind) {
        return "shops/" + shop.getId() + "/branding/" + kind.path;
    }

    /**
     * Старый файл удаляем только после коммита: если транзакция откатится, магазин продолжит ссылаться
     * на прежний ключ — и файл по нему должен остаться. Вне транзакции (тесты) — сразу.
     */
    private void deleteAfterCommit(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    s3FileStorage.delete(key);
                }
            });
        } else {
            s3FileStorage.delete(key);
        }
    }
}
