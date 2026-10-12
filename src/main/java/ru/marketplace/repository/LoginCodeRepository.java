package ru.marketplace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.LoginCode;

/** Ключ — нормализованная почта. */
@Repository
public interface LoginCodeRepository extends JpaRepository<LoginCode, String> {
}
