package ru.marketplace.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Какие ключи бакета на что-то ссылаются: фото товаров (product_photo.s3_key), логотип и фото витрины
 * (shop.logo_key / shop.hero_image_key). Нужен уборщику: всё остальное под shops/ — сироты загрузок.
 * Если появится новая колонка с ключом S3, её надо добавить сюда — иначе уборщик сочтёт файлы сиротами.
 */
@Repository
@RequiredArgsConstructor
public class StorageReferenceRepository {

    private static final String REFERENCED = """
            SELECT s3_key FROM product_photo WHERE s3_key IN (:keys)
            UNION
            SELECT logo_key FROM shop WHERE logo_key IN (:keys)
            UNION
            SELECT hero_image_key FROM shop WHERE hero_image_key IN (:keys)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Ключи из набора, на которые ссылается БД. */
    public Set<String> referencedAmong(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(REFERENCED, Map.of("keys", keys), String.class));
    }
}
