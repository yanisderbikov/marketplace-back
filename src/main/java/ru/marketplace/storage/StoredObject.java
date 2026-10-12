package ru.marketplace.storage;

import java.time.Instant;

/** Объект бакета из листинга: ключ и время последней записи. */
public record StoredObject(String key, Instant lastModified) {
}
