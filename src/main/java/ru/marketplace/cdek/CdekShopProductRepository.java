package ru.marketplace.cdek;

import org.springframework.data.repository.Repository;
import ru.marketplace.model.Product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Товары корзины для расчёта доставки одним запросом. Фильтр по магазину в самом запросе:
 * товар чужого магазина просто не найдётся, и его вес/габариты не попадут в расчёт.
 */
interface CdekShopProductRepository extends Repository<Product, UUID> {

    List<Product> findAllByShopIdAndIdIn(UUID shopId, Collection<UUID> ids);
}
