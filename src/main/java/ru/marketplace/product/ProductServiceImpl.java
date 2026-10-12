package ru.marketplace.product;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.ProductVariant;
import ru.marketplace.model.Shop;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductSaveRequestDTO;
import ru.marketplace.product.dto.ProductVariantRequestDTO;
import ru.marketplace.product.dto.PublicProductDTO;
import ru.marketplace.repository.ProductRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class ProductServiceImpl implements ProductService {

    static final String NOT_FOUND = "Товар не найден";
    static final String CROSSED_PRICE_TOO_LOW = "crossedPriceKopecks: зачёркнутая цена должна быть больше цены";
    static final String CATALOG_ORDER_MISMATCH = "Список товаров устарел — обновите страницу";

    private final CurrentMerchant currentMerchant;
    private final ShopLookup shopLookup;
    private final ProductRepository productRepository;
    private final ProductLockRepository productLockRepository;
    private final ProductPhotoService productPhotoService;
    private final ProductMapper productMapper;
    private final Clock clock;

    static ResponseStatusException productNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantProductDTO> getMerchantProducts() {
        Shop shop = currentMerchant.shop();
        return productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId()).stream()
                .map(productMapper::toMerchant)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantProductDTO getMerchantProduct(UUID id) {
        Shop shop = currentMerchant.shop();
        Product product = productRepository.findByIdAndShopId(id, shop.getId())
                .orElseThrow(ProductServiceImpl::productNotFound);
        return productMapper.toMerchant(product);
    }

    @Override
    @Transactional
    public MerchantProductDTO create(ProductSaveRequestDTO request) {
        Shop shop = currentMerchant.shop();
        validate(request);
        Product product = new Product();
        product.setShop(shop);
        applyFields(product, request);
        product.setOrderNumber(productRepository.findMaxOrderNumber(shop.getId()).map(max -> max + 1).orElse(0));
        Instant now = Instant.now(clock);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        applyVariants(product, request.getVariants());
        // Новый товар: save = persist, каскадом сохраняются и варианты (id проставляются сразу)
        Product saved = productRepository.save(product);
        productRepository.flush();
        return productMapper.toMerchant(saved);
    }

    @Override
    @Transactional
    public MerchantProductDTO update(UUID id, ProductSaveRequestDTO request) {
        Shop shop = currentMerchant.shop();
        Product product = lockOwn(shop, id);
        validate(request);
        applyFields(product, request);
        applyVariants(product, request.getVariants());
        // Варианты — отдельная таблица, их замена не трогает строку product. Меняем поле — строка «грязная»,
        // и @PreUpdate проставит время при flush: карточка изменилась, updated_at должен сдвинуться
        product.setUpdatedAt(Instant.now(clock));
        // Товар managed: хватает flush (новые варианты получат id, @PreUpdate — время); save/merge тут не нужен
        productRepository.flush();
        return productMapper.toMerchant(product);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Shop shop = currentMerchant.shop();
        Product product = lockOwn(shop, id);
        List<String> photoKeys = product.getPhotos().stream()
                .map(ProductPhoto::getS3Key)
                .toList();
        // Фото и варианты удалятся каскадом; в order_item product_id/variant_id обнулит FK (ON DELETE SET NULL),
        // а название и цена в заказе — снапшот, они остаются
        productRepository.delete(product);
        productRepository.flush();
        productPhotoService.deleteObjectsAfterCommit(photoKeys);
    }

    @Override
    @Transactional
    public List<MerchantProductDTO> reorder(List<UUID> productIds) {
        Shop shop = currentMerchant.shop();
        List<Product> products = productRepository.findAllByShopIdOrderByOrderNumberAscCreatedAtAsc(shop.getId());
        Map<UUID, Product> byId = products.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // Только свои товары и ровно все: чужой id, пропуск или повтор — у фронта устаревший список
        if (!ProductPhotoService.isSameSet(productIds, byId.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CATALOG_ORDER_MISMATCH);
        }
        for (int i = 0; i < productIds.size(); i++) {
            byId.get(productIds.get(i)).setOrderNumber(i);
        }
        productRepository.flush();
        return productIds.stream()
                .map(byId::get)
                .map(productMapper::toMerchant)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicProductDTO> getStorefrontProducts(String shopSlug) {
        Shop shop = shopLookup.activeBySlug(shopSlug);
        return productRepository.findAllByShopIdAndActiveTrueOrderByOrderNumberAscCreatedAtAsc(shop.getId()).stream()
                .map(productMapper::toPublic)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PublicProductDTO getStorefrontProduct(String shopSlug, String productId) {
        Shop shop = shopLookup.activeBySlug(shopSlug);
        UUID id = parseId(productId);
        return productRepository.findByIdAndShopId(id, shop.getId())
                .filter(product -> Boolean.TRUE.equals(product.getActive()))
                .map(productMapper::toPublic)
                .orElseThrow(ProductServiceImpl::productNotFound);
    }

    /** Товар своего магазина под блокировкой строки (см. ProductLockRepository); чужой — 404. */
    private Product lockOwn(Shop shop, UUID id) {
        return productLockRepository.findOwnForUpdate(id, shop.getId())
                .orElseThrow(ProductServiceImpl::productNotFound);
    }

    /**
     * id из адреса витрины (/shop/{slug}/product/{id}) — его можно испортить руками или прийти по старой ссылке;
     * покупателю это «товар не найден», а не «некорректный параметр».
     */
    private static UUID parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw productNotFound();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw productNotFound();
        }
    }

    /**
     * Проверки, которые не выразить аннотациями DTO (поля между собой, повтор варианта). Основные проверки —
     * аннотациями в контроллере (@Valid); обязательные поля здесь — страховка от NPE и 500 на NOT NULL колонке.
     * Формат текста — как у валидации: «поле: что не так».
     */
    private static void validate(ProductSaveRequestDTO request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw badRequest("name: укажите название товара");
        }
        if (request.getDescription() == null || request.getPriceKopecks() == null
                || request.getActive() == null || request.getPreorder() == null) {
            throw badRequest("Не заполнены обязательные поля товара");
        }
        Long price = request.getPriceKopecks();
        Long crossed = request.getCrossedPriceKopecks();
        if (crossed != null && crossed <= price) {
            throw badRequest(CROSSED_PRICE_TOO_LOW);
        }
        if (request.getVariants() != null) {
            Set<UUID> seenIds = new HashSet<>();
            for (ProductVariantRequestDTO variant : request.getVariants()) {
                if (variant == null || variant.getLabel() == null || variant.getLabel().isBlank()
                        || variant.getPriceKopecks() == null) {
                    throw badRequest("variants: у варианта товара должны быть заполнены название и цена");
                }
                if (variant.getId() != null && !seenIds.add(variant.getId())) {
                    throw badRequest("variants: один и тот же вариант указан дважды");
                }
            }
        }
    }

    /** PUT заменяет карточку целиком: null в необязательных полях (зачёркнутая цена, габариты) — очистить. */
    private static void applyFields(Product product, ProductSaveRequestDTO request) {
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setPriceKopecks(request.getPriceKopecks());
        product.setCrossedPriceKopecks(request.getCrossedPriceKopecks());
        product.setActive(request.getActive());
        product.setPreorder(request.getPreorder());
        product.setWeightGrams(request.getWeightGrams());
        product.setLengthCm(request.getLengthCm());
        product.setWidthCm(request.getWidthCm());
        product.setHeightCm(request.getHeightCm());
    }

    /**
     * Полная замена набора вариантов (перенос applyVariants из anyforms): вариант с id этого товара обновляется
     * на месте — так он остаётся в корзинах покупателей; без id или с незнакомым id создаётся новый;
     * не перечисленные удаляются (orphanRemoval). orderNumber = позиция в запросе.
     */
    private static void applyVariants(Product product, List<ProductVariantRequestDTO> requested) {
        Map<UUID, ProductVariant> existing = product.getVariants().stream()
                .filter(v -> v.getId() != null)
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        List<ProductVariant> result = new ArrayList<>();
        int position = 0;
        for (ProductVariantRequestDTO dto : requested == null ? List.<ProductVariantRequestDTO>of() : requested) {
            ProductVariant variant = dto.getId() == null ? null : existing.get(dto.getId());
            if (variant == null) {
                variant = new ProductVariant();
                variant.setProduct(product);
            }
            variant.setLabel(dto.getLabel().trim());
            variant.setPriceKopecks(dto.getPriceKopecks());
            variant.setOrderNumber(position++);
            result.add(variant);
        }
        product.getVariants().clear();
        product.getVariants().addAll(result);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
