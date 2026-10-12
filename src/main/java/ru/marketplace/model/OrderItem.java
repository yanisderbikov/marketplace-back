package ru.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Позиция заказа — снапшот на момент оформления: имя и цена копируются,
 * поэтому удаление/правка товара не меняет уже оплаченные заказы и аналитику.
 * productId/variantId — просто колонки без JPA-связи (при удалении товара БД обнуляет их).
 */
@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    /** Название товара на момент заказа */
    @Column(name = "product_name", nullable = false, length = 512)
    private String productName;

    @Column(name = "unit_price_kopecks", nullable = false)
    private Long unitPriceKopecks;

    @Column(nullable = false)
    private Integer quantity;
}
