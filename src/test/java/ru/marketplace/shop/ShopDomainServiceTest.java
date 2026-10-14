package ru.marketplace.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.domain.DomainProvisioner;
import ru.marketplace.domain.DomainProvisioningException;
import ru.marketplace.domain.dto.DnsRecordDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;
import ru.marketplace.model.Shop;
import ru.marketplace.repository.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopDomainServiceTest {

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    private final DomainProvisioner provisioner = mock(DomainProvisioner.class);
    private ShopDomainService service;
    private Shop shop;

    @BeforeEach
    void setUp() {
        service = new ShopDomainService(shopRepository, currentMerchant, new ShopDomains("https://market.example"),
                provisioner, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        shop = new Shop();
        shop.setId(UUID.randomUUID());
        shop.setSlug("candles");
        shop.setActive(true);
        when(currentMerchant.shop()).thenReturn(shop);
        when(shopRepository.findByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(shopRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(provisioner.attach(anyString())).thenAnswer(inv -> pending(inv.getArgument(0)));
    }

    private static ShopDomainDTO pending(String domain) {
        return new ShopDomainDTO(domain, ShopDomainDTO.PENDING, null,
                List.of(new DnsRecordDTO("CNAME", "www", domain, "x.up.railway.app", DnsRecordDTO.ROUTING, false)));
    }

    private static HttpStatus statusOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void saveNormalizesKeepsWwwAttachesAndReturnsDnsRecords() {
        ShopDomainDTO dto = service.save(" https://WWW.Shop.Example.ru/catalog ");

        assertEquals("www.shop.example.ru", shop.getCustomDomain());
        assertEquals("www.shop.example.ru", dto.domain());
        assertEquals("x.up.railway.app", dto.records().get(0).value());
        verify(provisioner).attach("www.shop.example.ru");
    }

    @Test
    void saveConvertsCyrillicToPunycode() {
        service.save("Магазин.рф");
        assertEquals("xn--80aairftm.xn--p1ai", shop.getCustomDomain());
    }

    @Test
    void changingDomainDetachesPreviousAndEmptyUnbinds() {
        shop.setCustomDomain("old.example.ru");

        service.save("new.example.ru");
        verify(provisioner).detach("old.example.ru");

        ShopDomainDTO none = service.save("  ");
        assertNull(shop.getCustomDomain());
        assertEquals(ShopDomainDTO.NONE, none.status());
        verify(provisioner).detach("new.example.ru");
    }

    @Test
    void unbindClearsDomainAndDetachesItFromGateway() {
        shop.setCustomDomain("shop.example.ru");

        assertEquals(ShopDomainDTO.NONE, service.unbind().status());
        assertNull(shop.getCustomDomain());
        verify(provisioner).detach("shop.example.ru");

        service.unbind();
        verify(provisioner, never()).detach(null);
    }

    @Test
    void rejectsBadDomainsAndPlatformDomain() {
        for (String bad : List.of("localhost", "shop..ru", "-shop.ru", "1.2.3.4", "shop.ru:8080",
                "market.example", "candles.market.example")) {
            assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> service.save(bad)), bad);
        }
        verify(provisioner, never()).attach(anyString());
    }

    @Test
    void domainOrItsWwwVariantOfAnotherShopIsConflict() {
        Shop other = new Shop();
        other.setId(UUID.randomUUID());
        when(shopRepository.findByCustomDomain("shop.example.ru")).thenReturn(Optional.of(other));

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> service.save("www.shop.example.ru")));
        verify(provisioner, never()).attach(anyString());
    }

    @Test
    void provisioningFailureIsBadGatewayAndDomainIsNotSaved() {
        when(provisioner.attach("shop.example.ru")).thenThrow(new DomainProvisioningException("down"));

        assertEquals(HttpStatus.BAD_GATEWAY, statusOf(() -> service.save("shop.example.ru")));
        assertNull(shop.getCustomDomain());
        verify(shopRepository, never()).saveAndFlush(any());
    }

    @Test
    void describeWithoutDomainIsNoneAndApiFailureIsError() {
        assertEquals(ShopDomainDTO.NONE, service.describe().status());

        shop.setCustomDomain("shop.example.ru");
        when(provisioner.describe("shop.example.ru")).thenThrow(new DomainProvisioningException("down"));
        assertEquals(ShopDomainDTO.ERROR, service.describe().status());
    }

    @Test
    void resolveMatchesWwwVariantPortAndCaseButHidesInactiveShops() {
        shop.setCustomDomain("www.shop.example.ru");
        when(shopRepository.findByCustomDomain("www.shop.example.ru")).thenReturn(Optional.of(shop));

        assertEquals("candles", service.resolve("Shop.Example.RU:443").slug());
        assertEquals("candles", service.resolve("www.shop.example.ru").slug());

        shop.setActive(false);
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.resolve("www.shop.example.ru")));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.resolve("unknown.example.ru")));
    }
}
