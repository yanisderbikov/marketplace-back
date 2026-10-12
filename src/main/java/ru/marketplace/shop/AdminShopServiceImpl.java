package ru.marketplace.shop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.repository.UserRepository;
import ru.marketplace.shop.dto.AdminShopDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Магазины глазами супер-админа: кто владелец, сколько товаров и оплаченных заказов,
 * готов ли принимать заказы. Выключение — рубильник платформы: витрина отвечает 404,
 * оформление заказа — 409, а сам мерчант по-прежнему входит в админку и видит свои данные.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AdminShopServiceImpl implements AdminShopService {

    private final ShopRepository shopRepository;
    private final ShopLookup shopLookup;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final AdminShopStatsRepository statsRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AdminShopDTO> list() {
        List<Shop> shops = shopRepository.findAllByOrderByCreatedAtDesc();
        if (shops.isEmpty()) {
            return List.of();
        }
        // Три сводных запроса на всю таблицу вместо четырёх на каждую строку
        Map<UUID, Long> products = statsRepository.countProductsByShop().stream()
                .collect(Collectors.toMap(AdminShopStatsRepository.ShopProducts::getShopId,
                        AdminShopStatsRepository.ShopProducts::getProductsCount));
        Map<UUID, AdminShopStatsRepository.ShopPaidOrders> paid = statsRepository.paidOrdersByShop().stream()
                .collect(Collectors.toMap(AdminShopStatsRepository.ShopPaidOrders::getShopId, Function.identity()));
        Map<UUID, String> owners = statsRepository.ownersByShop().stream()
                .collect(Collectors.toMap(AdminShopStatsRepository.ShopOwner::getShopId,
                        AdminShopStatsRepository.ShopOwner::getEmail));

        return shops.stream()
                .map(shop -> {
                    AdminShopStatsRepository.ShopPaidOrders shopPaid = paid.get(shop.getId());
                    return toDto(shop,
                            owners.get(shop.getId()),
                            products.get(shop.getId()),
                            shopPaid == null ? null : shopPaid.getOrdersCount(),
                            shopPaid == null ? null : shopPaid.getTotalKopecks());
                })
                .toList();
    }

    @Override
    @Transactional
    public AdminShopDTO setActive(UUID id, boolean active) {
        Shop shop = shopLookup.byId(id);
        if (!Objects.equals(shop.getActive(), active)) {
            shop.setActive(active);
            shopRepository.save(shop);
            log.info("Супер-админ {} магазин {} ({})", active ? "включил" : "выключил", shop.getSlug(), shop.getId());
        }
        return toDto(shop,
                userRepository.findByShopId(id).map(User::getEmail).orElse(null),
                productRepository.countByShopId(id),
                orderRepository.countByShopIdAndPaymentStatus(id, PaymentStatus.PAID),
                orderRepository.sumPaidByShop(id));
    }

    private static AdminShopDTO toDto(Shop shop, String ownerEmail, Long productsCount,
                                      Long paidOrdersCount, Long paidTotalKopecks) {
        return new AdminShopDTO(
                shop.getId(),
                shop.getSlug(),
                shop.getName(),
                Boolean.TRUE.equals(shop.getActive()),
                ownerEmail,
                shop.getCreatedAt(),
                zeroIfNull(productsCount),
                zeroIfNull(paidOrdersCount),
                zeroIfNull(paidTotalKopecks),
                ShopReadiness.acceptsOrders(shop));
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}
