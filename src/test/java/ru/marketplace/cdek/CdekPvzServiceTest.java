package ru.marketplace.cdek;

import org.junit.jupiter.api.Test;
import ru.marketplace.cdek.dto.CdekPvzDTO;
import ru.marketplace.repository.CdekPvzJdbcRepo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CdekPvzServiceTest {

    private static final CdekCredentials REVOKED = new CdekCredentials("revoked", "secret");
    private static final CdekCredentials LIVE = new CdekCredentials("live", "secret");

    private final CdekDeliveryPointsGateway gateway = mock(CdekDeliveryPointsGateway.class);
    private final CdekAccounts accounts = mock(CdekAccounts.class);
    private final CdekAuthService auth = mock(CdekAuthService.class);
    private final CdekPvzJdbcRepo repo = mock(CdekPvzJdbcRepo.class);
    private final CdekPvzService service = new CdekPvzService(gateway, accounts, auth, repo);

    private static CdekPvzDTO point(String code, String country, String city, String address) {
        return CdekPvzDTO.builder()
                .code(code)
                .countryCode(country)
                .city(city)
                .address(address)
                .fullAddress(city + ", " + address)
                .build();
    }

    private static final List<CdekPvzDTO> POINTS = List.of(
            point("ALA1", "KZ", "Алматы", "ул. Абая, 10"),
            point("PRM1", "RU", "Пермь", "ул. Грибоедова, 135"),
            point("MSK1", "RU", "Москва", "ул. Абая, 1"),
            point("MNS1", "BY", "Минск", "пр. Независимости, 5")
    );

    @Test
    void searchesAcrossCountriesAndPutsRussiaFirst() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();

        List<String> codes = service.search("абая").stream().map(CdekPvzDTO::getCode).toList();

        assertEquals(List.of("MSK1", "ALA1"), codes);
    }

    @Test
    void findsForeignPointByCity() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();

        List<CdekPvzDTO> found = service.search("минск независимости");

        assertEquals(1, found.size());
        assertEquals("MNS1", found.get(0).getCode());
    }

    @Test
    void shortQueryReturnsNothing() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();

        assertTrue(service.search("ул").isEmpty());
        assertTrue(service.search(null).isEmpty());
    }

    @Test
    void refreshUsesFirstShopAccountThatCdekAccepts() {
        when(accounts.connected()).thenReturn(List.of(REVOKED, LIVE));
        when(auth.getAccessToken(REVOKED)).thenReturn(null);
        when(auth.getAccessToken(LIVE)).thenReturn("tok");
        when(gateway.fetchAllPickupPoints(LIVE)).thenReturn(POINTS);

        assertTrue(service.refresh());

        verify(gateway, never()).fetchAllPickupPoints(REVOKED);
        verify(repo).replaceAll(POINTS);
        assertEquals(4, service.size());
    }

    @Test
    void refreshWithoutAnyConnectedShopKeepsCache() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();
        when(accounts.connected()).thenReturn(List.of());

        assertFalse(service.refresh());

        verify(gateway, never()).fetchAllPickupPoints(any());
        verify(repo, never()).replaceAll(anyList());
        assertEquals(4, service.size());
    }

    @Test
    void firstConnectedShopFillsEmptyDirectory() {
        when(accounts.connected()).thenReturn(List.of(LIVE));
        when(auth.getAccessToken(LIVE)).thenReturn("tok");
        when(gateway.fetchAllPickupPoints(LIVE)).thenReturn(POINTS);

        service.onCdekConnected(new CdekConnectedEvent("candles"));

        verify(repo, timeout(2000)).replaceAll(POINTS);
    }

    @Test
    void connectingAnotherShopDoesNotReloadFilledDirectory() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();

        service.onCdekConnected(new CdekConnectedEvent("candles"));

        verify(accounts, never()).connected();
    }

    @Test
    void refreshKeepsOldDataWhenApiReturnsNothing() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();
        when(accounts.connected()).thenReturn(List.of(LIVE));
        when(auth.getAccessToken(LIVE)).thenReturn("tok");
        when(gateway.fetchAllPickupPoints(LIVE)).thenReturn(List.of());

        assertFalse(service.refresh());

        verify(repo, never()).replaceAll(anyList());
        assertEquals(4, service.size());
    }

    @Test
    void findByCodeIsCaseInsensitiveOverSnapshot() {
        when(repo.findAll()).thenReturn(POINTS);
        service.warmUp();

        assertEquals("Москва", service.findByCode(" msk1 ").orElseThrow().getCity());
        assertTrue(service.findByCode("NOPE").isEmpty());
        assertTrue(service.findByCode(" ").isEmpty());
        assertTrue(service.findByCode(null).isEmpty());
        // Снимок загружен — в БД за каждым кодом не ходим
        verify(repo, never()).findByCode(anyString());
    }

    @Test
    void findByCodeFallsBackToDbUntilSnapshotLoaded() {
        when(repo.findByCode("MSK1")).thenReturn(List.of(POINTS.get(2)));
        when(repo.findByCode("NOPE")).thenReturn(List.of());

        assertEquals("MSK1", service.findByCode("MSK1 ").orElseThrow().getCode());
        assertTrue(service.findByCode("NOPE").isEmpty());

        when(repo.findByCode("BROKEN")).thenThrow(new IllegalStateException("db down"));
        assertTrue(service.findByCode("BROKEN").isEmpty());
    }
}
