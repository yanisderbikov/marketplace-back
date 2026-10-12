package ru.marketplace.storage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@Slf4j
@Service
public class S3FileStorageImpl implements S3FileStorage {

    /** Сколько живёт ссылка на чтение фото: бакет приватный, витрина отдаёт presigned GET */
    private static final Duration READ_PRESIGN_DURATION = Duration.ofHours(6);
    /**
     * Сколько отдаём одну и ту же ссылку. Каждая новая подпись — новый URL, и браузер заново
     * качает ту же картинку; кэш держит URL стабильным, а запас в час гарантирует,
     * что отданная ссылка проживёт ещё минимум час.
     */
    private static final Duration READ_URL_CACHE_TTL = Duration.ofHours(5);
    /** Окно на прямую загрузку файла из браузера в S3 */
    private static final Duration UPLOAD_PRESIGN_DURATION = Duration.ofMinutes(30);
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;
    private final Cache<String, String> readUrlCache = CacheBuilder.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(READ_URL_CACHE_TTL)
            .build();

    public S3FileStorageImpl(S3Client s3Client,
                             S3Presigner s3Presigner,
                             @Value("${s3.bucket.name}") String bucketName) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
    }

    @Override
    public PresignedUpload presignUpload(String keyPrefix, String filename, String contentType, Long size) {
        String type = contentType == null ? "" : contentType.trim();
        if (!IMAGE_CONTENT_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Можно загрузить только изображение: JPEG, PNG, WebP, GIF или AVIF");
        }
        if (size != null && size <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой файл");
        }
        if (size != null && size > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TOO_LARGE);
        }
        String prefix = keyPrefix == null ? "" : keyPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
        String ext = extractExtension(filename);
        String key = (prefix.isEmpty() ? "" : prefix + "/") + UUID.randomUUID() + ext;

        // Content-Type входит в подпись — браузер должен отправить PUT с тем же заголовком.
        // Content-Length тоже (если фронт прислал размер): подпись SigV4 включает content-length
        // в SignedHeaders, и PUT файла другого размера S3 отклонит SignatureDoesNotMatch
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(type)
                .contentLength(size)
                .build();
        String uploadUrl = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(UPLOAD_PRESIGN_DURATION)
                        .putObjectRequest(request)
                        .build())
                .url().toString();
        return new PresignedUpload(uploadUrl, key);
    }

    @Override
    public void requireUploaded(String key) {
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не загружен — попробуйте ещё раз");
            }
            log.warn("Не удалось проверить объект {} в S3 (HTTP {}): {}", key, e.statusCode(), e.getMessage());
            return;
        } catch (SdkException e) {
            // Сеть/DNS до хранилища: мерчанта не блокируем (см. javadoc интерфейса)
            log.warn("Не удалось проверить объект {} в S3: {}", key, e.getMessage());
            return;
        }
        Long length = head.contentLength();
        if (length != null && length > MAX_UPLOAD_BYTES) {
            // Загружен по старой подписи без длины: в бакете ему не место, к витрине не привязываем
            log.warn("Объект {} весит {} байт — больше лимита {}, удаляем", key, length, MAX_UPLOAD_BYTES);
            delete(key);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TOO_LARGE);
        }
    }

    @Override
    public void listObjects(String prefix, Predicate<List<StoredObject>> page) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();
        for (ListObjectsV2Response response : s3Client.listObjectsV2Paginator(request)) {
            List<StoredObject> objects = response.contents().stream()
                    .map(o -> new StoredObject(o.key(), o.lastModified()))
                    .toList();
            if (!objects.isEmpty() && !page.test(objects)) {
                return;
            }
        }
    }

    @Override
    public String publicUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String cached = readUrlCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        String url = presignGet(key);
        readUrlCache.put(key, url);
        return url;
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        readUrlCache.invalidate(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (Exception e) {
            // Осиротевший файл в бакете дешевле, чем упавшее удаление товара или смена логотипа
            log.warn("Не удалось удалить объект {} из S3: {}", key, e.getMessage());
        }
    }

    private String presignGet(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                .getObjectRequest(getObjectRequest)
                .signatureDuration(READ_PRESIGN_DURATION)
                .build();

        return s3Presigner.presignGetObject(getObjectPresignRequest).url().toString();
    }

    private static String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }
}
