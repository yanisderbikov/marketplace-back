package ru.marketplace.shop;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import ru.marketplace.model.Shop;

import java.util.List;
import java.util.UUID;

/**
 * Сводные цифры для таблицы супер-админа одним запросом на все магазины:
 * счётчики по каждому магазину отдельно (countByShopId, sumPaidByShop…) дали бы 4 запроса на строку.
 * Магазины без товаров/заказов/владельца в выборку не попадают — для них сервис подставляет 0 и null.
 */
interface AdminShopStatsRepository extends Repository<Shop, UUID> {

    interface ShopProducts {
        UUID getShopId();

        Long getProductsCount();
    }

    interface ShopPaidOrders {
        UUID getShopId();

        Long getOrdersCount();

        Long getTotalKopecks();
    }

    interface ShopOwner {
        UUID getShopId();

        String getEmail();
    }

    @Query("select p.shop.id as shopId, count(p) as productsCount from Product p group by p.shop.id")
    List<ShopProducts> countProductsByShop();

    @Query("""
            select o.shop.id as shopId, count(o) as ordersCount, sum(o.itemsTotalKopecks) as totalKopecks
            from Order o
            where o.paymentStatus = ru.marketplace.model.PaymentStatus.PAID
            group by o.shop.id
            """)
    List<ShopPaidOrders> paidOrdersByShop();

    @Query("select u.shop.id as shopId, u.email as email from User u where u.shop is not null")
    List<ShopOwner> ownersByShop();
}
