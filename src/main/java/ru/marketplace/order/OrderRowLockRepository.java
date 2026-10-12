package ru.marketplace.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.marketplace.model.Order;

import java.util.Optional;
import java.util.UUID;

/**
 * Заказ под блокировкой строки (SELECT … FOR UPDATE) — для изменений, которые делает модуль заказов.
 * Письма об оплате: вебхук, поллер и страница успеха могут почти одновременно увидеть PAID и позвать
 * onOrderPaid — без блокировки оба прочли бы paidNotified=false и покупатель получил бы два письма.
 * Смена исполнения: Hibernate пишет строку целиком, и без блокировки сохранение мерчанта могло бы
 * затереть параллельно записанный флаг писем.
 * Отдельный репозиторий, а не метод в OrderRepository: блокировки нужны только этому модулю.
 */
interface OrderRowLockRepository extends Repository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** Заказ своего магазина: чужой не находится вовсе (и не блокируется) — снаружи это 404. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.publicId = :publicId and o.shop.id = :shopId")
    Optional<Order> findOwnForUpdate(@Param("publicId") String publicId, @Param("shopId") UUID shopId);
}
