package ru.marketplace.cdek;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.cdek.dto.CdekLocation;
import ru.marketplace.cdek.dto.CdekPackage;
import ru.marketplace.cdek.dto.CdekTariffQuote;
import ru.marketplace.cdek.dto.DeliveryCostItemDTO;
import ru.marketplace.cdek.dto.DeliveryCostRequestDTO;
import ru.marketplace.cdek.dto.DeliveryCostResponseDTO;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.Product;
import ru.marketplace.model.Shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Расчёт доставки СДЭК на чекауте витрины: от ПВЗ отправки магазина до ПВЗ покупателя, по ключам СДЭК
 * самого магазина — тарифы его договора, а не платформы.
 * Каждая единица товара — отдельное место посылки с весом/габаритами товара (как в anyforms).
 * Расчёт только информирует покупателя: доставка оплачивается при получении и в платёж не входит.
 * Без @Transactional: соединение с БД не держим, пока ждём ответ СДЭК (до 20 секунд).
 */
@Service
public class CdekDeliveryCostService {

    private static final Logger logger = LoggerFactory.getLogger(CdekDeliveryCostService.class);

    /**
     * Упаковка товара, у которого мерчант не заполнил вес/габариты. В anyforms это были env-переменные
     * платформы; здесь каталоги у всех разные, и это константа предметной области, а не настройка деплоя
     */
    static final int DEFAULT_WEIGHT_GRAMS = 300;
    static final int DEFAULT_DIMENSION_CM = 15;
    /** Страна для адреса отправки из настроек магазина, когда ПВЗ пропал из справочника: продавцы — российские (ИНН) */
    private static final String FALLBACK_COUNTRY = "RU";
    private static final String NO_SENDER_PVZ = "Магазин пока не указал пункт отправки СДЭК";
    private static final String NO_CDEK_ACCOUNT = "Магазин пока не подключил СДЭК";

    private final CdekCalculatorGateway calculatorGateway;
    private final CdekAccounts cdekAccounts;
    private final CdekPvzLookup pvzLookup;
    private final ShopLookup shopLookup;
    private final CdekShopProductRepository productRepository;
    private final int tariffCode;

    public CdekDeliveryCostService(CdekCalculatorGateway calculatorGateway,
                                   CdekAccounts cdekAccounts,
                                   CdekPvzLookup pvzLookup,
                                   ShopLookup shopLookup,
                                   CdekShopProductRepository productRepository,
                                   @Value("${sdek.tariff.code}") int tariffCode) {
        this.calculatorGateway = calculatorGateway;
        this.cdekAccounts = cdekAccounts;
        this.pvzLookup = pvzLookup;
        this.shopLookup = shopLookup;
        this.productRepository = productRepository;
        this.tariffCode = tariffCode;
    }

    public DeliveryCostResponseDTO calculate(String slug, DeliveryCostRequestDTO request) {
        Shop shop = shopLookup.activeBySlug(slug);
        // Сначала магазин: если ему не с чего отправлять, адрес покупателя и корзина уже не важны
        CdekLocation from = fromLocation(shop);
        CdekCredentials credentials = cdekAccounts.of(shop)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, NO_CDEK_ACCOUNT));
        String pvzCode = request.getPvzCode();
        CdekLocation to = pvzLookup.findByCode(pvzCode)
                .map(CdekLocation::fromPvz)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "ПВЗ не найден: " + pvzCode));
        List<CdekPackage> packages = buildPackages(shop.getId(), request.getItems());
        CdekTariffQuote quote = calculatorGateway.calculate(credentials, tariffCode, from, to, packages);
        return new DeliveryCostResponseDTO(quote.deliverySum(), quote.periodMin(), quote.periodMax(), tariffCode);
    }

    List<CdekPackage> buildPackages(UUID shopId, List<DeliveryCostItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Корзина пуста");
        }
        Set<UUID> ids = items.stream()
                .filter(Objects::nonNull)
                .map(DeliveryCostItemDTO::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Product> products = ids.isEmpty()
                ? Map.of()
                : productRepository.findAllByShopIdAndIdIn(shopId, ids).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));

        List<CdekPackage> packages = new ArrayList<>();
        for (DeliveryCostItemDTO item : items) {
            if (item == null || item.getProductId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указан товар");
            }
            Product product = products.get(item.getProductId());
            if (product == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Товар не найден: " + item.getProductId());
            }
            int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
            if (quantity < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Некорректное количество для товара " + product.getName());
            }
            CdekPackage unit = packageFor(product);
            for (int i = 0; i < quantity; i++) {
                packages.add(unit);
            }
        }
        return packages;
    }

    private static CdekPackage packageFor(Product product) {
        return new CdekPackage(
                positiveOrDefault(product.getWeightGrams(), DEFAULT_WEIGHT_GRAMS),
                positiveOrDefault(product.getLengthCm(), DEFAULT_DIMENSION_CM),
                positiveOrDefault(product.getWidthCm(), DEFAULT_DIMENSION_CM),
                positiveOrDefault(product.getHeightCm(), DEFAULT_DIMENSION_CM));
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /**
     * ПВЗ отправки магазина: из справочника, а если пункт пропал из него (СДЭК закрыл ПВЗ, а мерчант ещё
     * не сменил) — по городу/индексу/адресу, скопированным в магазин при сохранении настроек.
     */
    private CdekLocation fromLocation(Shop shop) {
        String code = shop.getCdekFromPvzCode();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, NO_SENDER_PVZ);
        }
        return pvzLookup.findByCode(code)
                .map(CdekLocation::fromPvz)
                .orElseGet(() -> {
                    if (isBlank(shop.getCdekFromCity()) && isBlank(shop.getCdekFromPostalCode())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, NO_SENDER_PVZ);
                    }
                    logger.warn("ПВЗ отправки {} магазина {} не найден в справочнике, используем адрес из настроек магазина",
                            code, shop.getSlug());
                    return new CdekLocation(FALLBACK_COUNTRY, shop.getCdekFromPostalCode(),
                            shop.getCdekFromCity(), shop.getCdekFromAddress());
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
