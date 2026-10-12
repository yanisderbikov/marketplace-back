package ru.marketplace.order;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.model.Order;
import ru.marketplace.model.OrderItem;
import ru.marketplace.model.Shop;
import ru.marketplace.order.dto.ProductSalesDTO;
import ru.marketplace.order.dto.SalesReportDTO;
import ru.marketplace.repository.OrderRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Перенос anyforms ShopSalesReportServiceImpl. Отличия: магазин — всегда свой (CurrentMerchant),
 * сумма заказа — itemsTotalKopecks (платёж Т-Банка равен ей до копейки, промокодов и синка АМО нет),
 * а дни считаются по Москве явно, а не по часовому поясу сервера.
 */
@Service
@RequiredArgsConstructor
class SalesReportServiceImpl implements SalesReportService {

    /**
     * Дни отчёта — московские: «сегодня» у мерчанта начинается в 00:00 МСК, а не в 00:00 UTC
     * (иначе ночные заказы с 00:00 до 03:00 уезжали бы во вчерашний день).
     */
    static final ZoneId REPORT_ZONE = ZoneId.of("Europe/Moscow");

    private final OrderRepository orderRepository;
    private final CurrentMerchant currentMerchant;

    @Override
    @Transactional(readOnly = true)
    public SalesReportDTO getReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите период отчёта");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Дата начала позже даты конца");
        }
        Shop shop = currentMerchant.shop();

        // Диапазон включительный по дням: [from 00:00 МСК, to+1 00:00 МСК)
        List<Order> orders = orderRepository.findPaidByShopBetween(shop.getId(), startOfDay(from), startOfDay(to.plusDays(1)));

        long itemsCount = 0;
        long totalKopecks = 0;
        Map<String, ProductRow> byProduct = new LinkedHashMap<>();

        for (Order order : orders) {
            totalKopecks += nvl(order.getItemsTotalKopecks());
            for (OrderItem item : order.getItems()) {
                long quantity = nvl(item.getQuantity());
                itemsCount += quantity;
                ProductRow row = byProduct.computeIfAbsent(item.getProductName(), name -> new ProductRow());
                row.quantity += quantity;
                row.totalKopecks += nvl(item.getUnitPriceKopecks()) * quantity;
            }
        }

        List<ProductSalesDTO> products = byProduct.entrySet().stream()
                .map(e -> new ProductSalesDTO(e.getKey(), e.getValue().quantity, e.getValue().totalKopecks))
                .sorted(Comparator.comparingLong(ProductSalesDTO::totalKopecks).reversed()
                        .thenComparing(Comparator.comparingLong(ProductSalesDTO::quantity).reversed())
                        .thenComparing(ProductSalesDTO::productName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new SalesReportDTO(
                shop.getSlug(),
                shop.getName(),
                from,
                to,
                orders.size(),
                itemsCount,
                totalKopecks,
                products);
    }

    static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(REPORT_ZONE).toInstant();
    }

    /** Накопитель строки разбивки: количество и сумма по одному названию товара. */
    private static final class ProductRow {
        private long quantity;
        private long totalKopecks;
    }

    private static long nvl(Number value) {
        return value == null ? 0 : value.longValue();
    }
}
