package ru.marketplace.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.PaymentStatus;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.OrderRepository;
import ru.marketplace.repository.ProductRepository;
import ru.marketplace.repository.ShopRepository;
import ru.marketplace.repository.UserRepository;
import ru.marketplace.shop.dto.AdminShopDTO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminShopServiceImplTest {

    private static final UUID READY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NEW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED = Instant.parse("2026-10-01T09:00:00Z");

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final AdminShopStatsRepository statsRepository = mock(AdminShopStatsRepository.class);
    private AdminShopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminShopServiceImpl(shopRepository, new ShopLookup(shopRepository), userRepository,
                productRepository, orderRepository, statsRepository);
        when(shopRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Магазин, у которого настроено всё для приёма заказов */
    private static Shop readyShop() {
        Shop shop = new Shop();
        shop.setId(READY_ID);
        shop.setSlug("candles");
        shop.setName("Свечи");
        shop.setActive(true);
        shop.setCreatedAt(CREATED);
        shop.setTinkoffTerminalKey("1700000000000DEMO");
        shop.setTinkoffPasswordEnc("enc");
        shop.setCdekFromPvzCode("MSK123");
        shop.setCdekClientId("cdek-account");
        shop.setCdekClientSecretEnc("enc");
        shop.setSellerName("ИП Иванов Иван Иванович");
        shop.setSellerInn("123456789012");
        return shop;
    }

    /** Только что созданный магазин: ничего не настроено */
    private static Shop newShop() {
        Shop shop = new Shop();
        shop.setId(NEW_ID);
        shop.setSlug("new-shop");
        shop.setName("Новый");
        shop.setActive(true);
        shop.setCreatedAt(CREATED.plusSeconds(60));
        return shop;
    }

    private static AdminShopStatsRepository.ShopProducts products(UUID shopId, long count) {
        return new AdminShopStatsRepository.ShopProducts() {
            public UUID getShopId() {
                return shopId;
            }

            public Long getProductsCount() {
                return count;
            }
        };
    }

    private static AdminShopStatsRepository.ShopPaidOrders paid(UUID shopId, long orders, long total) {
        return new AdminShopStatsRepository.ShopPaidOrders() {
            public UUID getShopId() {
                return shopId;
            }

            public Long getOrdersCount() {
                return orders;
            }

            public Long getTotalKopecks() {
                return total;
            }
        };
    }

    private static AdminShopStatsRepository.ShopOwner owner(UUID shopId, String email) {
        return new AdminShopStatsRepository.ShopOwner() {
            public UUID getShopId() {
                return shopId;
            }

            public String getEmail() {
                return email;
            }
        };
    }

    @Test
    void listJoinsAggregatesInOrderAndDefaultsMissingToZero() {
        when(shopRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newShop(), readyShop()));
        when(statsRepository.countProductsByShop()).thenReturn(List.of(products(READY_ID, 12)));
        when(statsRepository.paidOrdersByShop()).thenReturn(List.of(paid(READY_ID, 3, 357_000)));
        when(statsRepository.ownersByShop()).thenReturn(List.of(owner(READY_ID, "owner@example.com")));

        List<AdminShopDTO> list = service.list();

        assertEquals(List.of(
                new AdminShopDTO(NEW_ID, "new-shop", "Новый", true, null, CREATED.plusSeconds(60),
                        0, 0, 0, false),
                new AdminShopDTO(READY_ID, "candles", "Свечи", true, "owner@example.com", CREATED,
                        12, 3, 357_000, true)
        ), list);
        // Сводные цифры — тремя запросами на всю таблицу, без запросов на каждую строку
        verifyNoInteractions(productRepository, orderRepository, userRepository);
    }

    @Test
    void emptyPlatformSkipsAggregates() {
        when(shopRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertEquals(List.of(), service.list());
        verifyNoInteractions(statsRepository);
    }

    @Test
    void disablingShopSavesItAndStopsOrders() {
        Shop shop = readyShop();
        when(shopRepository.findById(READY_ID)).thenReturn(Optional.of(shop));
        User owner = new User();
        owner.setEmail("owner@example.com");
        when(userRepository.findByShopId(READY_ID)).thenReturn(Optional.of(owner));
        when(productRepository.countByShopId(READY_ID)).thenReturn(12L);
        when(orderRepository.countByShopIdAndPaymentStatus(READY_ID, PaymentStatus.PAID)).thenReturn(3L);
        when(orderRepository.sumPaidByShop(READY_ID)).thenReturn(357_000L);

        AdminShopDTO dto = service.setActive(READY_ID, false);

        assertFalse(shop.getActive());
        verify(shopRepository).save(shop);
        assertEquals(new AdminShopDTO(READY_ID, "candles", "Свечи", false, "owner@example.com", CREATED,
                12, 3, 357_000, false), dto);
    }

    @Test
    void enablingShopRestoresOrdersWhenConfigured() {
        Shop shop = readyShop();
        shop.setActive(false);
        when(shopRepository.findById(READY_ID)).thenReturn(Optional.of(shop));
        when(userRepository.findByShopId(READY_ID)).thenReturn(Optional.empty());

        AdminShopDTO dto = service.setActive(READY_ID, true);

        assertTrue(dto.active());
        assertTrue(dto.acceptsOrders());
        assertNull(dto.ownerEmail());
        assertEquals(0, dto.paidTotalKopecks());
        verify(shopRepository).save(shop);
    }

    @Test
    void sameValueIsNotSavedAgain() {
        when(shopRepository.findById(NEW_ID)).thenReturn(Optional.of(newShop()));
        when(userRepository.findByShopId(NEW_ID)).thenReturn(Optional.empty());

        AdminShopDTO dto = service.setActive(NEW_ID, true);

        assertTrue(dto.active());
        assertFalse(dto.acceptsOrders());
        verify(shopRepository, never()).save(any());
    }

    @Test
    void unknownShopIs404() {
        UUID unknown = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(shopRepository.findById(unknown)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.setActive(unknown, false));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(shopRepository, never()).save(any());
    }
}
