package ru.marketplace.shop;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import ru.marketplace.model.Shop;

import java.util.List;
import java.util.UUID;

/**
 * Число продающихся товаров по включённым магазинам — для главной платформы одним запросом,
 * а не countByShopIdAndActiveTrue на каждую карточку. Магазин без активных товаров в выборку
 * не попадает: пустую витрину на главной не показываем.
 */
interface ShopCatalogStatsRepository extends Repository<Shop, UUID> {

    interface ShopActiveProducts {
        UUID getShopId();

        Long getProductsCount();
    }

    @Query("""
            select p.shop.id as shopId, count(p) as productsCount
            from Product p
            where p.active = true and p.shop.active = true
            group by p.shop.id
            """)
    List<ShopActiveProducts> countActiveProductsOfActiveShops();
}
