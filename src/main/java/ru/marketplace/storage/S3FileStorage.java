package ru.marketplace.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.Predicate;

/**
 * Key-based операции с S3. Загрузка идёт напрямую из браузера по presigned PUT —
 * бэкенд файлы не проксирует, только подписывает URL. Бакет приватный: чтение — по presigned GET.
 */
public interface S3FileStorage {

    /**
     * Потолок размера одной картинки (как на фронте): presigned PUT сам по себе не ограничивает размер,
     * и без серверного лимита один URL принимал бы до 5 ГБ за счёт платформы.
     */
    long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    String TOO_LARGE = "Файл больше 10 МБ";

    /**
     * Presigned PUT URL под ключом {@code keyPrefix/uuid.ext} (от исходного имени остаётся только расширение).
     * Content-Type входит в подпись: браузер обязан отправить PUT с тем же заголовком.
     * Если size передан, в подпись входит и Content-Length: S3 отклонит PUT файла другого размера
     * (браузер выставляет Content-Length сам). size == null — старый фронт без размера: подпись без длины,
     * размер тогда проверяет {@link #requireUploaded} при привязке.
     * 400, если contentType не картинка (jpeg, png, webp, gif, avif) или size не в 1..{@link #MAX_UPLOAD_BYTES}.
     */
    PresignedUpload presignUpload(String keyPrefix, String filename, String contentType, Long size);

    /**
     * Проверка файла перед привязкой ключа к товару/витрине: объект есть в бакете и не больше
     * {@link #MAX_UPLOAD_BYTES}. Слишком большой удаляется сразу. 400 — файла нет или он слишком большой.
     * Если хранилище не ответило (сеть, 5xx) — только лог: загрузить файл в недоступное хранилище нельзя,
     * а размер новых загрузок держит подпись, остальное уберёт уборщик сирот.
     */
    void requireUploaded(String key);

    /**
     * Объекты под префиксом, постранично (для уборщика осиротевших загрузок).
     * Обработчик страницы возвращает false — листинг дальше не идёт.
     */
    void listObjects(String prefix, Predicate<List<StoredObject>> page);

    /** Presigned GET URL для ключа (живёт 6 часов); null → null. */
    String publicUrl(String key);

    /** Удаляет объект; null/пустой ключ — no-op; ошибки S3 только логируются. */
    void delete(String key);

    /**
     * Проверка ключа, который фронт прислал после загрузки: он должен лежать под ожидаемым префиксом
     * (например shops/{shopId}/products/{productId}/), иначе мерчант мог бы привязать к своему товару
     * чужой файл. Возвращает ключ без пробелов по краям; 400, если ключ чужой или подозрительный.
     */
    static String requireKeyUnder(String key, String keyPrefix) {
        String prefix = keyPrefix == null ? "" : keyPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
        String trimmed = key == null ? "" : key.trim();
        if (prefix.isEmpty() || trimmed.isEmpty()
                || !trimmed.startsWith(prefix + "/")
                || trimmed.length() == prefix.length() + 1
                || trimmed.contains("..")
                || trimmed.contains("//")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный ключ файла");
        }
        return trimmed;
    }
}
