package ru.marketplace.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.PaymentStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    /**
     * Оплаченные заказы магазина с paidAt в [from, toExclusive) — основа отчёта продаж.
     * Позиции подтягиваются сразу: отчёт группирует их по названию товара.
     */
    @Query("""
            select distinct o from Order o
            left join fetch o.items
            where o.shop.id = :shopId
              and o.paymentStatus = ru.marketplace.model.PaymentStatus.PAID
              and o.paidAt >= :from
              and o.paidAt < :toExclusive
            order by o.paidAt asc
            """)
    List<Order> findPaidByShopBetween(@Param("shopId") UUID shopId,
                                      @Param("from") Instant from,
                                      @Param("toExclusive") Instant toExclusive);

    /** Список заказов мерчанта с фильтрами по оплате и исполнению; сортировку задаёт Pageable */
    Page<Order> findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(UUID shopId,
                                                                    Collection<PaymentStatus> paymentStatuses,
                                                                    Collection<FulfillmentStatus> fulfillmentStatuses,
                                                                    Pageable pageable);

    /**
     * Неоплаченные заказы для поллера оплаты: платёж создан, заказ свежее createdAfter,
     * GetState ещё не спрашивали или спрашивали раньше checkedBefore.
     * Магазин подтягивается сразу — для запроса статуса нужны его ключи у провайдера.
     */
    @Query("""
            select o from Order o
            join fetch o.shop
            where o.paymentStatus = ru.marketplace.model.PaymentStatus.AWAITING_PAYMENT
              and o.paymentId is not null
              and o.createdAt > :createdAfter
              and (o.paymentCheckedAt is null or o.paymentCheckedAt < :checkedBefore)
            order by o.createdAt asc
            """)
    List<Order> findAwaitingForCheck(@Param("createdAfter") Instant createdAfter,
                                     @Param("checkedBefore") Instant checkedBefore);

    /** Заказы в статусе, созданные раньше момента: поллер переводит зависшие AWAITING_PAYMENT старше суток в FAILED */
    List<Order> findAllByPaymentStatusAndCreatedAtBefore(PaymentStatus paymentStatus, Instant createdBefore);

    long countByShopIdAndPaymentStatus(UUID shopId, PaymentStatus paymentStatus);

    /** Выручка магазина за всё время (только оплаченные), для таблицы супер-админа */
    @Query("""
            select coalesce(sum(o.itemsTotalKopecks), 0) from Order o
            where o.shop.id = :shopId
              and o.paymentStatus = ru.marketplace.model.PaymentStatus.PAID
            """)
    Long sumPaidByShop(@Param("shopId") UUID shopId);
}
