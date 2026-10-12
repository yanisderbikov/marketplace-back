package ru.marketplace.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.marketplace.model.Product;

import java.util.Optional;
import java.util.UUID;

/**
 * Товар своего магазина под блокировкой строки (SELECT … FOR UPDATE) — для изменений карточки, фото и вариантов.
 * Без неё два параллельных запроса к одному товару (ретрай сети, две вкладки админки) читают один и тот же
 * список фото: оба проходят лимит в 20 фото, получают одинаковую position или дважды привязывают один ключ,
 * а параллельная замена вариантов падает на уже удалённой строке.
 */
interface ProductLockRepository extends Repository<Product, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.shop.id = :shopId")
    Optional<Product> findOwnForUpdate(@Param("id") UUID id, @Param("shopId") UUID shopId);
}
