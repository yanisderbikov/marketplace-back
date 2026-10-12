package ru.marketplace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.Shop;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {

    Optional<Shop> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Shop> findAllByActiveTrueOrderByCreatedAtDesc();

    /** Все магазины для супер-админа, новые сверху */
    List<Shop> findAllByOrderByCreatedAtDesc();
}
