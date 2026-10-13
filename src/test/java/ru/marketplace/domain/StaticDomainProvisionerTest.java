package ru.marketplace.domain;

import org.junit.jupiter.api.Test;
import ru.marketplace.domain.dto.DnsRecordDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaticDomainProvisionerTest {

    private final DnsLookup dns = mock(DnsLookup.class);

    @Test
    void hostLabelIsRelativeToRegistrableDomain() {
        assertEquals("@", StaticDomainProvisioner.hostLabel("example.ru"));
        assertEquals("shop", StaticDomainProvisioner.hostLabel("shop.example.ru"));
        assertEquals("www", StaticDomainProvisioner.hostLabel("www.shop.com.ru"));
        assertEquals("@", StaticDomainProvisioner.hostLabel("xn--80aairftm.xn--p1ai"));
    }

    @Test
    void subdomainGetsCnameAndBecomesActiveWhenVisible() {
        when(dns.lookup(anyString(), anyString())).thenReturn(Set.of());
        StaticDomainProvisioner provisioner = new StaticDomainProvisioner(dns, "shops.market.ru.", "");

        ShopDomainDTO pending = provisioner.attach("shop.example.ru");
        DnsRecordDTO record = pending.records().get(0);
        assertEquals("CNAME", record.type());
        assertEquals("shop", record.host());
        assertEquals("shops.market.ru", record.value());
        assertEquals(ShopDomainDTO.PENDING, pending.status());

        when(dns.lookup("shop.example.ru", "CNAME")).thenReturn(Set.of("shops.market.ru"));
        assertEquals(ShopDomainDTO.ACTIVE, provisioner.describe("shop.example.ru").status());
    }

    @Test
    void apexGetsARecordWhenIpConfiguredOtherwiseCnameWithHint() {
        when(dns.lookup(anyString(), anyString())).thenReturn(Set.of());
        DnsRecordDTO a = new StaticDomainProvisioner(dns, "shops.market.ru", "203.0.113.7")
                .describe("example.ru").records().get(0);
        assertEquals("A", a.type());
        assertEquals("@", a.host());
        assertEquals("203.0.113.7", a.value());

        ShopDomainDTO cname = new StaticDomainProvisioner(dns, "shops.market.ru", "").describe("example.ru");
        assertEquals("CNAME", cname.records().get(0).type());
        assertNotNull(cname.message());
        assertTrue(cname.message().contains("ALIAS"));
    }
}
