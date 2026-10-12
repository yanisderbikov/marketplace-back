package ru.marketplace.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Заказ покупателя в магазине. Создаётся сразу при оформлении (AWAITING_PAYMENT),
 * дальше статус оплаты двигают вебхук и поллер провайдера оплаты, статус исполнения — мерчант.
 * Сумма — только товары: доставка СДЭК оплачивается при получении и в платёж не входит.
 * Без @Builder по той же причине, что и Product: items всегда список, не null.
 */
@Entity
@Table(name = "orders")
// UPDATE только изменённых колонок: заказ правят и вебхук/поллер оплаты, и мерчант (исполнение), и письма
// (paidNotified) — сохранение одной стороны не перезапишет поля, которые параллельно изменила другая
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Номер заказа для людей, OrderId в Т-Банке и metadata.order_id в ЮKassa (6 символов, PublicIdGenerator) */
    @Column(name = "public_id", nullable = false, unique = true, length = 8, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false, updatable = false)
    private Shop shop;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 32)
    private FulfillmentStatus fulfillmentStatus;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 32)
    private String customerPhone;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "pvz_code", nullable = false, length = 64)
    private String pvzCode;

    @Column(name = "pvz_city")
    private String pvzCity;

    @Column(name = "pvz_address", length = 512)
    private String pvzAddress;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "marketing_consent", nullable = false)
    private Boolean marketingConsent;

    /** Сумма товаров по серверным ценам = сумма платежа у провайдера */
    @Column(name = "items_total_kopecks", nullable = false)
    private Long itemsTotalKopecks;

    @Column(name = "tracking_number", length = 64)
    private String trackingNumber;

    /** Через кого создан платёж; статус спрашиваем у него же, даже если магазин потом сменил провайдера */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", nullable = false, length = 16, updatable = false)
    private PaymentProvider paymentProvider;

    /** PaymentId Т-Банка или id платежа ЮKassa */
    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    /** Когда последний раз спрашивали статус у провайдера — чтобы поллер и страница успеха его не долбили */
    @Column(name = "payment_checked_at")
    private Instant paymentCheckedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Письма об оплате уже ушли — повторное «оплачено» от провайдера не шлёт их второй раз */
    @Column(name = "paid_notified", nullable = false)
    private Boolean paidNotified;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
