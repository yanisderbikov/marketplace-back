package ru.marketplace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.Product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** Каталог в админке: все товары, включая выключенные */
    List<Product> findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(UUID shopId);

    /** Витрина: только включённые */
    List<Product> findAllByShopIdAndActiveTrueOrderByOrderNumberAscCreatedAtAsc(UUID shopId);

    /** Товар только своего магазина: чужой id для мерчанта выглядит как «нет такого» */
    Optional<Product> findByIdAndShopId(UUID id, UUID shopId);

    long countByShopIdAndActiveTrue(UUID shopId);

    long countByShopId(UUID shopId);

    /** Новый товар встаёт в конец каталога: max + 1 (пусто — магазин без товаров) */
    @Query("select max(p.orderNumber) from Product p where p.shop.id = :shopId")
    Optional<Integer> findMaxOrderNumber(@Param("shopId") UUID shopId);
}
