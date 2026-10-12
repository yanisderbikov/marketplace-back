package ru.marketplace.shop;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;

/**
 * Привязка нового магазина к владельцу условным UPDATE … WHERE shop_id IS NULL.
 * users.shop_id UNIQUE не мешает одному пользователю создать два магазина: два параллельных онбординга
 * (двойной клик) оба увидели бы shop = null и последний перезаписал бы первого, оставив магазин-сироту
 * с занятым адресом. Здесь второй UPDATE дождётся первого, обновит 0 строк — и его транзакция откатится.
 */
interface ShopOwnerRepository extends Repository<User, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update User u set u.shop = :shop where u.id = :userId and u.shop is null")
    int attachShopIfNone(@Param("userId") Long userId, @Param("shop") Shop shop);
}
