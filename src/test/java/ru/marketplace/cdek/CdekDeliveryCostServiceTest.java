package ru.marketplace.cdek;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.cdek.dto.CdekLocation;
import ru.marketplace.cdek.dto.CdekPackage;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.cdek.dto.CdekTariffQuote;
import ru.marketplace.cdek.dto.DeliveryCostItemDTO;
import ru.marketplace.cdek.dto.DeliveryCostRequestDTO;
import ru.marketplace.cdek.dto.DeliveryCostResponseDTO;
import ru.marketplace.common.ShopLookup;
import ru.marketplace.model.Product;
import ru.marketplace.model.Shop;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CdekDeliveryCostServiceTest {

    private static final int TARIFF = 136;
    private static final String SLUG = "candles";
    private static final String FROM_PVZ = "SPB75";
    private static final UUID SHOP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final CdekCredentials KEYS = new CdekCredentials("shop-account", "shop-secret");

    private final CdekCalculatorGateway gateway = mock(CdekCalculatorGateway.class);
    private final CdekAccounts accounts = mock(CdekAccounts.class);
    private final CdekPvzLookup pvzLookup = mock(CdekPvzLookup.class);
    private final ShopLookup shopLookup = mock(ShopLookup.class);
    private final CdekShopProductRepository products = mock(CdekShopProductRepository.class);
    private final CdekDeliveryCostService service =
            new CdekDeliveryCostService(gateway, accounts, pvzLookup, shopLookup, products, TARIFF);

    @BeforeEach
    void shopHasOwnCdekAccount() {
        when(accounts.of(any())).thenReturn(Optional.of(KEYS));
    }

    private static CdekPvzDTO pvz(String code, String city, String postal, String address) {
        return CdekPvzDTO.builder().code(code).countryCode("RU").city(city).postalCode(postal).address(address).build();
    }

    private static Shop shop(String fromPvz) {
        return Shop.builder()
                .id(SHOP_ID).slug(SLUG).name("Свечи").active(true)
                .cdekFromPvzCode(fromPvz)
                .cdekFromCity("Санкт-Петербург").cdekFromPostalCode("198095").cdekFromAddress("ул. Трефолева, 9, корп. 2")
                .build();
    }

    private static Product product(UUID id, String name, Integer weight, Integer length, Integer width, Integer height) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setWeightGrams(weight);
        p.setLengthCm(length);
        p.setWidthCm(width);
        p.setHeightCm(height);
        return p;
    }

    private static DeliveryCostItemDTO item(UUID productId, int quantity) {
        DeliveryCostItemDTO dto = new DeliveryCostItemDTO();
        dto.setProductId(productId);
        dto.setQuantity(quantity);
        return dto;
    }

    private static DeliveryCostRequestDTO request(String pvzCode, DeliveryCostItemDTO... items) {
        DeliveryCostRequestDTO dto = new DeliveryCostRequestDTO();
        dto.setPvzCode(pvzCode);
        dto.setItems(List.of(items));
        return dto;
    }

    @Test
    void usesDefaultsWhenProductHasNoDimensionsAndOnePackagePerUnit() {
        UUID noDims = UUID.randomUUID();
        UUID withDims = UUID.randomUUID();
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(products.findAllByShopIdAndIdIn(eq(SHOP_ID), anyCollection())).thenReturn(List.of(
                product(noDims, "Без габаритов", null, null, 0, null),
                product(withDims, "С габаритами", 1200, 28, 26, 10)));
        when(pvzLookup.findByCode("MSK1")).thenReturn(Optional.of(pvz("MSK1", "Москва", "101000", "ул. Абая, 1")));
        when(pvzLookup.findByCode(FROM_PVZ)).thenReturn(Optional.of(pvz(FROM_PVZ, "Санкт-Петербург", "198095", "ул. Трефолева, 9, корп. 2")));
        when(gateway.calculate(any(), anyInt(), any(), any(), anyList()))
                .thenReturn(new CdekTariffQuote(new BigDecimal("450.00"), 2, 4));

        DeliveryCostResponseDTO response = service.calculate(SLUG, request("MSK1", item(noDims, 2), item(withDims, 1)));

        assertEquals(new BigDecimal("450.00"), response.cost());
        assertEquals(2, response.periodMin());
        assertEquals(4, response.periodMax());
        assertEquals(TARIFF, response.tariffCode());

        ArgumentCaptor<CdekLocation> from = ArgumentCaptor.forClass(CdekLocation.class);
        ArgumentCaptor<CdekLocation> to = ArgumentCaptor.forClass(CdekLocation.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CdekPackage>> packages = ArgumentCaptor.forClass(List.class);
        verify(gateway).calculate(eq(KEYS), eq(TARIFF), from.capture(), to.capture(), packages.capture());
        assertEquals("198095", from.getValue().postalCode());
        assertEquals("101000", to.getValue().postalCode());
        assertEquals(List.of(
                new CdekPackage(300, 15, 15, 15),
                new CdekPackage(300, 15, 15, 15),
                new CdekPackage(1200, 28, 26, 10)), packages.getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(products).findAllByShopIdAndIdIn(eq(SHOP_ID), ids.capture());
        assertEquals(Set.of(noDims, withDims), Set.copyOf(ids.getValue()));
    }

    @Test
    void fallsBackToShopSavedAddressWhenFromPvzLeftDirectory() {
        UUID id = UUID.randomUUID();
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(products.findAllByShopIdAndIdIn(eq(SHOP_ID), anyCollection()))
                .thenReturn(List.of(product(id, "x", null, null, null, null)));
        when(pvzLookup.findByCode("MSK1")).thenReturn(Optional.of(pvz("MSK1", "Москва", "101000", "ул. Абая, 1")));
        when(pvzLookup.findByCode(FROM_PVZ)).thenReturn(Optional.empty());
        when(gateway.calculate(any(), anyInt(), any(), any(), anyList()))
                .thenReturn(new CdekTariffQuote(new BigDecimal("300"), 1, 2));

        service.calculate(SLUG, request("MSK1", item(id, 1)));

        ArgumentCaptor<CdekLocation> from = ArgumentCaptor.forClass(CdekLocation.class);
        verify(gateway).calculate(any(), anyInt(), from.capture(), any(), anyList());
        assertEquals(new CdekLocation("RU", "198095", "Санкт-Петербург", "ул. Трефолева, 9, корп. 2"), from.getValue());
    }

    @Test
    void shopWithoutSenderPvzIs409() {
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(null));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("MSK1", item(UUID.randomUUID(), 1))));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals("Магазин пока не указал пункт отправки СДЭК", e.getReason());
        verify(gateway, never()).calculate(any(), anyInt(), any(), any(), anyList());
    }

    @Test
    void shopWithoutOwnCdekAccountIs409() {
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(pvzLookup.findByCode(FROM_PVZ)).thenReturn(Optional.of(pvz(FROM_PVZ, "Санкт-Петербург", "198095", "ул. Трефолева")));
        when(accounts.of(any())).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("MSK1", item(UUID.randomUUID(), 1))));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals("Магазин пока не подключил СДЭК", e.getReason());
        verify(gateway, never()).calculate(any(), anyInt(), any(), any(), anyList());
    }

    @Test
    void senderPvzGoneAndNoSavedAddressIs409() {
        Shop shop = shop(FROM_PVZ);
        shop.setCdekFromCity(null);
        shop.setCdekFromPostalCode(" ");
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop);
        when(pvzLookup.findByCode(FROM_PVZ)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("MSK1", item(UUID.randomUUID(), 1))));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void productOfAnotherShopIs400() {
        UUID own = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(pvzLookup.findByCode(any())).thenReturn(Optional.of(pvz("MSK1", "Москва", "101000", "ул. Абая, 1")));
        // Репозиторий фильтрует по магазину — чужой товар просто не возвращается
        when(products.findAllByShopIdAndIdIn(eq(SHOP_ID), anyCollection()))
                .thenReturn(List.of(product(own, "Свой", null, null, null, null)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("MSK1", item(own, 1), item(foreign, 1))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals("Товар не найден: " + foreign, e.getReason());
        verify(gateway, never()).calculate(any(), anyInt(), any(), any(), anyList());
    }

    @Test
    void rejectsUnknownPvzAndEmptyCart() {
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(pvzLookup.findByCode(FROM_PVZ)).thenReturn(Optional.of(pvz(FROM_PVZ, "Санкт-Петербург", "198095", "ул. Трефолева")));
        when(pvzLookup.findByCode("NOPE")).thenReturn(Optional.empty());
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("NOPE", item(UUID.randomUUID(), 1))));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
        assertEquals("ПВЗ не найден: NOPE", unknown.getReason());

        when(pvzLookup.findByCode("MSK1")).thenReturn(Optional.of(pvz("MSK1", "Москва", "101000", "ул. Абая, 1")));
        ResponseStatusException empty = assertThrows(ResponseStatusException.class,
                () -> service.calculate(SLUG, request("MSK1")));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals("Корзина пуста", empty.getReason());
    }

    @Test
    void inactiveOrMissingShopPropagates404() {
        when(shopLookup.activeBySlug("gone"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Магазин не найден"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.calculate("gone", request("MSK1", item(UUID.randomUUID(), 1))));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void calculationErrorPropagatesForController() {
        UUID id = UUID.randomUUID();
        when(shopLookup.activeBySlug(SLUG)).thenReturn(shop(FROM_PVZ));
        when(pvzLookup.findByCode(any())).thenReturn(Optional.of(pvz("MSK1", "Москва", "101000", "ул. Абая, 1")));
        when(products.findAllByShopIdAndIdIn(eq(SHOP_ID), anyCollection()))
                .thenReturn(List.of(product(id, "x", null, null, null, null)));
        when(gateway.calculate(any(), anyInt(), any(), any(), anyList()))
                .thenThrow(new CdekCalculationException("Невозможно осуществить доставку"));

        assertThrows(CdekCalculationException.class, () -> service.calculate(SLUG, request("MSK1", item(id, 1))));
    }
}
