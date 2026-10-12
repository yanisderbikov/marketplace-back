package ru.marketplace.cdek;

import ru.marketplace.cdek.dto.CdekPvzDTO;

import java.util.List;

/**
 * Полный справочник ПВЗ СДЭК по ключам одного из магазинов: справочник у всех аккаунтов один.
 * Пустой список — «не удалось загрузить», кэш в этом случае не трогаем.
 */
public interface CdekDeliveryPointsGateway {

    List<CdekPvzDTO> fetchAllPickupPoints(CdekCredentials credentials);
}
