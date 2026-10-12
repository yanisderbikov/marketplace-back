package ru.marketplace.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.marketplace.model.Order;

import java.time.Instant;
import java.util.Optional;

/**
 * Запросы к заказам, нужные только оплате. Отдельно от общего OrderRepository:
 * блокировка строки для смены статуса и точечные UPDATE, которые не затирают чужие изменения.
 */
@Repository
public interface PaymentOrderRepository extends JpaRepository<Order, Long> {

    /**
     * Заказ под SELECT … FOR UPDATE: вебхук, поллер и страница успеха могут прийти одновременно —
     * блокировка выстраивает их в очередь, и перевод в PAID (а значит, и письма) случается ровно один раз.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** Заказ с магазином — для вебхука Т-Банка нужны TerminalKey и пароль терминала магазина. */
    @Query("select o from Order o join fetch o.shop where o.publicId = :publicId")
    Optional<Order> findWithShopByPublicId(@Param("publicId") String publicId);

    /** Заказ с магазином по id платежа — для уведомлений ЮKassa (в них нет номера заказа, только платёж). */
    @Query("select o from Order o join fetch o.shop where o.paymentId = :paymentId")
    Optional<Order> findWithShopByPaymentId(@Param("paymentId") String paymentId);

    /** Заказ с магазином и позициями одним запросом — для страницы статуса (open-in-view выключен). */
    @Query("select distinct o from Order o join fetch o.shop left join fetch o.items where o.publicId = :publicId")
    Optional<Order> findWithShopAndItemsByPublicId(@Param("publicId") String publicId);

    /** Платёж создан: запоминаем id платежа и ссылку точечным UPDATE, не трогая статус. */
    @Transactional
    @Modifying
    @Query("""
            update Order o
            set o.paymentId = :paymentId, o.paymentUrl = :paymentUrl, o.updatedAt = :now
            where o.id = :id
            """)
    int savePayment(@Param("id") Long id,
                    @Param("paymentId") String paymentId,
                    @Param("paymentUrl") String paymentUrl,
                    @Param("now") Instant now);

    /** Платёж не создался: заказ в FAILED, если его ещё никто не перевёл в другой статус. */
    @Transactional
    @Modifying
    @Query("""
            update Order o
            set o.paymentStatus = ru.marketplace.model.PaymentStatus.FAILED, o.updatedAt = :now
            where o.id = :id
              and o.paymentStatus = ru.marketplace.model.PaymentStatus.AWAITING_PAYMENT
            """)
    int failIfAwaiting(@Param("id") Long id, @Param("now") Instant now);

    /** Брошенные заказы: всё ещё ждут оплаты, а созданы раньше createdBefore — FAILED одним запросом. */
    @Transactional
    @Modifying
    @Query("""
            update Order o
            set o.paymentStatus = ru.marketplace.model.PaymentStatus.FAILED, o.updatedAt = :now
            where o.paymentStatus = ru.marketplace.model.PaymentStatus.AWAITING_PAYMENT
              and o.createdAt < :createdBefore
            """)
    int failAwaitingCreatedBefore(@Param("createdBefore") Instant createdBefore, @Param("now") Instant now);

    /**
     * «Застолбить» проверку у банка: отметить paymentCheckedAt = now, только если заказ ждёт оплаты, платёж
     * создан и банк не спрашивали с момента threshold. Один атомарный UPDATE: из параллельных запросов
     * (страница успеха пачкой, страница + поллер) строку обновит ровно один — он и идёт в GetState.
     * Раньше решение принималось по прочитанному без блокировки снимку, а отметка ставилась только после
     * ответа банка — и все запросы, пришедшие за время GetState, тоже шли в банк.
     *
     * @return 1 — проверка за вызывающим, 0 — её уже делает/сделал кто-то другой или заказ не ждёт оплаты
     */
    @Transactional
    @Modifying
    @Query("""
            update Order o
            set o.paymentCheckedAt = :now
            where o.id = :id
              and o.paymentStatus = ru.marketplace.model.PaymentStatus.AWAITING_PAYMENT
              and o.paymentId is not null
              and (o.paymentCheckedAt is null or o.paymentCheckedAt < :threshold)
            """)
    int claimPaymentCheck(@Param("id") Long id, @Param("now") Instant now, @Param("threshold") Instant threshold);

    /** Отметка «GetState спрашивали» без смены статуса — когда банк не ответил, чтобы не долбить его каждый тик. */
    @Transactional
    @Modifying
    @Query("update Order o set o.paymentCheckedAt = :checkedAt where o.id = :id")
    int markPaymentChecked(@Param("id") Long id, @Param("checkedAt") Instant checkedAt);
}
