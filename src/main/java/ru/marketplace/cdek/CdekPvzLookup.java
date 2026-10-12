package ru.marketplace.cdek;

import ru.marketplace.cdek.dto.CdekPvzDTO;

import java.util.Optional;

/**
 * Поиск ПВЗ СДЭК по коду в локальном кэше. Узкий интерфейс для настроек магазина:
 * мерчант указывает код ПВЗ отправки, а город/индекс/адрес берутся отсюда, а не из формы.
 */
public interface CdekPvzLookup {
    Optional<CdekPvzDTO> findByCode(String code);
}
