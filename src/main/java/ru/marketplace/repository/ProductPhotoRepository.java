package ru.marketplace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marketplace.model.ProductPhoto;

import java.util.UUID;

@Repository
public interface ProductPhotoRepository extends JpaRepository<ProductPhoto, UUID> {
}
