package ru.marketplace.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Товар каталога магазина. Цены в копейках. Порядок в каталоге — orderNumber (меняется стрелками в админке).
 * Без @Builder: коллекции фото и вариантов должны быть пустыми списками, а не null,
 * а @Builder.Default на сущностях не используем.
 */
@Entity
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false, updatable = false)
    private Shop shop;

    @Column(nullable = false)
    private String name;

    /** Обязательна, но может быть пустой строкой */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "price_kopecks", nullable = false)
    private Long priceKopecks;

    /** Зачёркнутая «старая» цена; null или больше priceKopecks */
    @Column(name = "crossed_price_kopecks")
    private Long crossedPriceKopecks;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Boolean preorder;

    @Column(name = "order_number", nullable = false)
    private Integer orderNumber;

    /** Вес и габариты для расчёта доставки СДЭК; null — берутся значения упаковки по умолчанию */
    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "length_cm")
    private Integer lengthCm;

    @Column(name = "width_cm")
    private Integer widthCm;

    @Column(name = "height_cm")
    private Integer heightCm;

    /** Первое фото — главное (обложка карточки). BatchSize — чтобы список каталога не делал запрос на каждый товар */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 100)
    private List<ProductPhoto> photos = new ArrayList<>();

    /** Варианты (размер, цвет…) со своей ценой; если есть — покупатель обязан выбрать вариант */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderNumber ASC")
    @BatchSize(size = 100)
    private List<ProductVariant> variants = new ArrayList<>();

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
