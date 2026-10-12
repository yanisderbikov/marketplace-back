package ru.marketplace.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Уборщик осиротевших загрузок. Presigned PUT кладёт файл в бакет раньше, чем фронт привяжет ключ, —
 * брошенная загрузка (закрыли вкладку, ошибка привязки, злоупотребление подписями) навсегда оставалась бы
 * в бакете за счёт платформы. Lifecycle-правило бакета тут не помогает: оно знает только возраст и префикс
 * и удалило бы и живые фото.
 * <p>
 * Раз в несколько часов листаем shops/ и удаляем объекты старше суток, на которые не ссылается ни
 * product_photo, ни shop (StorageReferenceRepository). Сутки — с огромным запасом: подпись живёт 30 минут,
 * а фронт привязывает ключ сразу после загрузки, так что старый непривязанный файл привязан уже не будет.
 * Трогаем только ключи ровно нашего формата (shops/{uuid}/products/{uuid}/… и shops/{uuid}/branding/{logo|hero}/…):
 * чужое в бакете не удаляем никогда. Ошибка БД обрывает проход до удаления — лучше оставить сироту, чем живой файл.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanUploadSweeper {

    static final String ROOT_PREFIX = "shops/";
    static final Duration MIN_AGE = Duration.ofHours(24);
    /** Сколько ключей сверяем с БД одним запросом */
    static final int BATCH = 500;
    /** Предохранитель: за один проход не больше — если что-то пошло не так, ущерб ограничен */
    static final int MAX_DELETES_PER_RUN = 5_000;
    private static final Pattern UPLOAD_KEY = Pattern.compile(
            "^shops/[0-9a-f-]{36}/(?:products/[0-9a-f-]{36}|branding/(?:logo|hero))/[^/]+$");

    private final S3FileStorage s3FileStorage;
    private final StorageReferenceRepository storageReferenceRepository;
    private final Clock clock;

    /** Первый проход через час после старта (не мешать деплою), дальше — раз в 6 часов. */
    @Scheduled(initialDelay = 1, fixedDelay = 6, timeUnit = TimeUnit.HOURS)
    public void tick() {
        try {
            int deleted = sweep();
            if (deleted > 0) {
                log.info("Уборка бакета: удалено осиротевших загрузок {}", deleted);
            }
        } catch (Exception e) {
            log.error("Уборка осиротевших загрузок в S3 не сработала", e);
        }
    }

    /** @return сколько объектов удалено */
    int sweep() {
        Instant cutoff = clock.instant().minus(MIN_AGE);
        int[] deleted = {0};
        s3FileStorage.listObjects(ROOT_PREFIX, page -> {
            List<String> candidates = page.stream()
                    .filter(o -> o.lastModified() != null && o.lastModified().isBefore(cutoff))
                    .map(StoredObject::key)
                    .filter(key -> key != null && UPLOAD_KEY.matcher(key).matches())
                    .toList();
            for (int from = 0; from < candidates.size(); from += BATCH) {
                List<String> batch = candidates.subList(from, Math.min(from + BATCH, candidates.size()));
                Set<String> referenced = storageReferenceRepository.referencedAmong(batch);
                for (String key : batch) {
                    if (referenced.contains(key)) {
                        continue;
                    }
                    if (deleted[0] >= MAX_DELETES_PER_RUN) {
                        log.warn("Уборка бакета: достигнут предел {} удалений за проход, остальное — в следующий раз",
                                MAX_DELETES_PER_RUN);
                        return false;
                    }
                    s3FileStorage.delete(key);
                    deleted[0]++;
                }
            }
            return true;
        });
        return deleted[0];
    }
}
