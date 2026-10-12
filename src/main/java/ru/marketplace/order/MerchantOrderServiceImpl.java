package ru.marketplace.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.common.PageDTO;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.Order;
import ru.marketplace.model.OrderItem;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.order.dto.FulfillmentUpdateRequestDTO;
import ru.marketplace.order.dto.MerchantOrderDTO;
import ru.marketplace.order.dto.MerchantOrderItemDTO;
import ru.marketplace.repository.OrderRepository;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Заказы глазами мерчанта. Магазин всегда берётся из CurrentMerchant, а не из запроса:
 * чужой заказ не попадает в список и на смену статуса отвечает 404, как будто его нет.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class MerchantOrderServiceImpl implements MerchantOrderService {

    /** Фильтр «все статусы» */
    static final String ALL = "ALL";
    /** Больше 100 заказов за раз не отдаём: страница грузит позиции каждого заказа */
    static final int MAX_PAGE_SIZE = 100;
    /** Как колонка orders.tracking_number */
    static final int MAX_TRACKING_LENGTH = 64;

    /** Новые сверху; id — чтобы порядок заказов одной секунды не прыгал между страницами */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final OrderRowLockRepository orderRowLockRepository;
    private final CurrentMerchant currentMerchant;

    @Override
    @Transactional(readOnly = true)
    public PageDTO<MerchantOrderDTO> list(String payment, String fulfillment, int page, int size) {
        Set<PaymentStatus> paymentStatuses = parseFilter(payment, PaymentStatus.class, "Некорректный фильтр оплаты: ");
        Set<FulfillmentStatus> fulfillmentStatuses =
                parseFilter(fulfillment, FulfillmentStatus.class, "Некорректный фильтр исполнения: ");
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Номер страницы не может быть отрицательным");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Размер страницы должен быть больше нуля");
        }
        Shop shop = currentMerchant.shop();
        Page<Order> orders = orderRepository.findByShopIdAndPaymentStatusInAndFulfillmentStatusIn(
                shop.getId(), paymentStatuses, fulfillmentStatuses,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), NEWEST_FIRST));
        // Позиции ленивые — маппим внутри транзакции; @BatchSize на Order.items грузит их одним запросом на страницу
        return PageDTO.of(orders, MerchantOrderServiceImpl::toDto);
    }

    @Override
    @Transactional
    public MerchantOrderDTO updateFulfillment(String publicId, FulfillmentUpdateRequestDTO request) {
        if (request == null || request.getFulfillmentStatus() == null || request.getFulfillmentStatus().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите статус исполнения");
        }
        FulfillmentStatus status = parseStatus(request.getFulfillmentStatus(), FulfillmentStatus.class,
                "Некорректный статус исполнения: ");
        String trackingNumber = normalizeTracking(request.getTrackingNumber());

        Shop shop = currentMerchant.shop();
        String normalizedId = publicId == null ? "" : publicId.trim().toUpperCase(Locale.ROOT);
        Order order = orderRowLockRepository.findOwnForUpdate(normalizedId, shop.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        // Неоплаченный заказ исполнять нечего: деньги не пришли или платёж отменён/возвращён
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Статус исполнения можно менять только у оплаченного заказа");
        }

        if (order.getFulfillmentStatus() != status) {
            log.info("Заказ {} магазина {}: исполнение {} → {}", order.getPublicId(), shop.getSlug(),
                    order.getFulfillmentStatus(), status);
        }
        order.setFulfillmentStatus(status);
        order.setTrackingNumber(trackingNumber);
        orderRepository.save(order);
        return toDto(order);
    }

    static MerchantOrderDTO toDto(Order order) {
        List<MerchantOrderItemDTO> items = order.getItems().stream()
                // Порядок позиций — как при оформлении (id растёт), а не как вернула БД
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> new MerchantOrderItemDTO(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity() == null ? 0 : item.getQuantity(),
                        item.getUnitPriceKopecks() == null ? 0L : item.getUnitPriceKopecks()))
                .toList();
        return new MerchantOrderDTO(
                order.getPublicId(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getPaymentStatus(),
                order.getFulfillmentStatus(),
                order.getTrackingNumber(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                order.getCustomerEmail(),
                order.getPvzCode(),
                order.getPvzCity(),
                order.getPvzAddress(),
                order.getComment(),
                order.getItemsTotalKopecks() == null ? 0L : order.getItemsTotalKopecks(),
                items);
    }

    /** Фильтр списка: конкретный статус или ALL — все значения enum. */
    static <E extends Enum<E>> Set<E> parseFilter(String value, Class<E> type, String errorPrefix) {
        if (value != null && ALL.equalsIgnoreCase(value.trim())) {
            return EnumSet.allOf(type);
        }
        return EnumSet.of(parseStatus(value, type, errorPrefix));
    }

    /** Имя статуса без учёта регистра и пробелов по краям; неизвестное — 400 с тем, что прислали. */
    static <E extends Enum<E>> E parseStatus(String value, Class<E> type, String errorPrefix) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorPrefix + (value == null ? "" : value.trim()));
    }

    /** Пусто — трек-номера нет (null); длиннее колонки — 400, а не обрезка: обрезанный трек бесполезен. */
    static String normalizeTracking(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TRACKING_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Трек-номер — не длиннее " + MAX_TRACKING_LENGTH + " символов");
        }
        return trimmed;
    }
}
