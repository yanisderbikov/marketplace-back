package ru.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Магазин мерчанта: витрина /shop/{slug}, оформление, реквизиты продавца,
 * ПВЗ отправки и свой аккаунт СДЭК, собственные терминал Т-Банка и магазин ЮKassa (деньги идут мерчанту напрямую).
 * Значения по умолчанию не задаём — всё проставляется явно при создании магазина.
 */
@Entity
@Table(name = "shop")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Адрес витрины; после создания не меняется — на него ведут ссылки покупателей */
    @Column(nullable = false, unique = true, length = 32, updatable = false)
    private String slug;

    @Column(nullable = false, length = 120)
    private String name;

    /**
     * Свой домен витрины (punycode, нижний регистр) — витрина открывается на нём с корня.
     * null — только /shop/{slug} на домене платформы
     */
    @Column(name = "custom_domain", unique = true, length = 253)
    private String customDomain;

    /** Выключенный супер-админом магазин: витрина 404, оформление заказа 409 */
    @Column(nullable = false)
    private Boolean active;

    /** #rrggbb в нижнем регистре — из него фронт строит всю тему витрины */
    @Column(name = "brand_color", nullable = false, length = 7)
    private String brandColor;

    @Column(name = "logo_key", length = 512)
    private String logoKey;

    @Column(name = "hero_image_key", length = 512)
    private String heroImageKey;

    @Column(length = 160)
    private String tagline;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String about;

    /** Username телеграма поддержки, без @ */
    @Column(name = "support_telegram", length = 64)
    private String supportTelegram;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "seller_inn", length = 12)
    private String sellerInn;

    @Column(name = "seller_ogrn", length = 15)
    private String sellerOgrn;

    @Column(name = "seller_address", length = 512)
    private String sellerAddress;

    /** ПВЗ СДЭК, откуда мерчант отправляет посылки; город/индекс/адрес — копия из кэша ПВЗ */
    @Column(name = "cdek_from_pvz_code", length = 64)
    private String cdekFromPvzCode;

    @Column(name = "cdek_from_city")
    private String cdekFromCity;

    @Column(name = "cdek_from_postal_code", length = 16)
    private String cdekFromPostalCode;

    @Column(name = "cdek_from_address", length = 512)
    private String cdekFromAddress;

    /** Account из личного кабинета СДЭК: доставку магазин подключает по своему договору */
    @Column(name = "cdek_client_id", length = 128)
    private String cdekClientId;

    /** Secure password СДЭК, зашифрованный SecretCipher; наружу не отдаётся никогда */
    @Column(name = "cdek_client_secret_enc", columnDefinition = "TEXT")
    private String cdekClientSecretEnc;

    /**
     * Через кого витрина принимает оплату. null — не выбран явно (магазины до ЮKassa): тогда
     * провайдер — тот, что подключён (см. ShopPayment.activeProvider)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", length = 16)
    private PaymentProvider paymentProvider;

    @Column(name = "tinkoff_terminal_key", length = 64)
    private String tinkoffTerminalKey;

    /** Пароль терминала, зашифрованный SecretCipher; наружу не отдаётся никогда */
    @Column(name = "tinkoff_password_enc", columnDefinition = "TEXT")
    private String tinkoffPasswordEnc;

    @Column(name = "tinkoff_receipt_enabled", nullable = false)
    private Boolean tinkoffReceiptEnabled;

    /** Система налогообложения для чека: osn, usn_income, usn_income_outcome, esn, patent */
    @Column(name = "tinkoff_taxation", length = 32)
    private String tinkoffTaxation;

    /** Ставка НДС для позиций чека: none, vat0, vat5, vat7, vat10, vat22 */
    @Column(name = "tinkoff_tax", length = 16)
    private String tinkoffTax;

    /** shopId магазина в ЮKassa (число из личного кабинета) */
    @Column(name = "yookassa_shop_id", length = 32)
    private String yookassaShopId;

    /** Секретный ключ API ЮKassa, зашифрованный SecretCipher; наружу не отдаётся никогда */
    @Column(name = "yookassa_secret_key_enc", columnDefinition = "TEXT")
    private String yookassaSecretKeyEnc;

    @Column(name = "yookassa_receipt_enabled", nullable = false)
    private Boolean yookassaReceiptEnabled;

    /** Система налогообложения для чека ЮKassa — те же коды, что у Т-Банка (osn, usn_income, …) */
    @Column(name = "yookassa_taxation", length = 32)
    private String yookassaTaxation;

    /** Ставка НДС для чека ЮKassa — те же коды, что у Т-Банка (none, vat0, …, vat22) */
    @Column(name = "yookassa_tax", length = 16)
    private String yookassaTax;

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
