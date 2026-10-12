package ru.marketplace.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.User;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Магазин подгружается сразу: open-in-view выключен, и ленивый User.shop
     * вне транзакции (CurrentMerchant.shop(), /api/auth/me) иначе падал бы LazyInitializationException.
     */
    @EntityGraph(attributePaths = "shop")
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByShopId(UUID shopId);
}
