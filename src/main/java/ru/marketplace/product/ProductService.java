package ru.marketplace.product;

import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.product.dto.PublicProductDTO;

import java.util.List;
import java.util.UUID;

/**
 * Каталог магазина. Мерчантские методы всегда работают с магазином текущего пользователя (CurrentMerchant.shop()):
 * товар чужого магазина для них выглядит как несуществующий — 404, а не 403, чтобы не подтверждать, что такой id есть.
 */
public interface ProductService {

    /** Все товары своего магазина, включая выключенные, в порядке каталога. */
    List<MerchantProductDTO> getMerchantProducts();

    MerchantProductDTO getMerchantProduct(UUID id);

    /** Новый товар встаёт в конец каталога (orderNumber = max + 1). */
    MerchantProductDTO create(ProductSaveRequestDTO request);

    /** Полная замена полей карточки и набора вариантов. */
    MerchantProductDTO update(UUID id, ProductSaveRequestDTO request);

    /** Удаляет товар с вариантами и фото (объекты S3 — после коммита); в заказах остаётся снапшот названия. */
    void delete(UUID id);

    /** Порядок каталога: ровно все товары магазина, orderNumber = индекс в списке. */
    List<MerchantProductDTO> reorder(List<UUID> productIds);

    /** Витрина: включённые товары включённого магазина. Нет/выключен магазин — 404. */
    List<PublicProductDTO> getStorefrontProducts(String shopSlug);

    /** Карточка товара на витрине. Выключенный, чужой или кривой id — 404. */
    PublicProductDTO getStorefrontProduct(String shopSlug, String productId);
}
