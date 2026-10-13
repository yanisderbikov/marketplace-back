package ru.marketplace.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.marketplace.domain.dto.DnsRecordDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailwayDomainProvisionerTest {

    private static JsonNode json(String raw) throws Exception {
        return new ObjectMapper().readTree(raw.replace('\'', '"'));
    }

    @Test
    void pendingDomainGetsCnameAndVerificationTxt() throws Exception {
        JsonNode node = json("""
                {'id':'d1','domain':'shop.example.ru','status':{
                  'verified':false,'verificationDnsHost':'_railway-verify.shop','verificationToken':'railway-verify=abc',
                  'certificateStatus':'CERTIFICATE_STATUS_TYPE_VALIDATING_OWNERSHIP',
                  'dnsRecords':[{'hostlabel':'shop','fqdn':'shop.example.ru','zone':'example.ru',
                    'recordType':'DNS_RECORD_TYPE_CNAME','requiredValue':'0q0dw0yp.up.railway.app','currentValue':'',
                    'status':'DNS_RECORD_STATUS_REQUIRES_UPDATE','purpose':'DNS_RECORD_PURPOSE_TRAFFIC_ROUTE'}]}}
                """);

        ShopDomainDTO dto = RailwayDomainProvisioner.toDto("shop.example.ru", node);

        assertEquals(ShopDomainDTO.PENDING, dto.status());
        assertEquals(2, dto.records().size());
        DnsRecordDTO cname = dto.records().get(0);
        assertEquals("CNAME", cname.type());
        assertEquals("shop", cname.host());
        assertEquals("0q0dw0yp.up.railway.app", cname.value());
        assertEquals(DnsRecordDTO.ROUTING, cname.purpose());
        assertFalse(cname.ok());
        DnsRecordDTO txt = dto.records().get(1);
        assertEquals("TXT", txt.type());
        assertEquals("_railway-verify.shop", txt.host());
        assertEquals("_railway-verify.shop.example.ru", txt.fqdn());
        assertEquals("railway-verify=abc", txt.value());
        assertEquals(DnsRecordDTO.VERIFICATION, txt.purpose());
    }

    @Test
    void apexHostIsAtSignAndBareTokenGetsPrefix() throws Exception {
        JsonNode node = json("""
                {'status':{'verified':false,'verificationDnsHost':'_railway-verify','verificationToken':'abc',
                  'certificateStatus':'','dnsRecords':[{'hostlabel':'','fqdn':'example.ru','zone':'example.ru',
                    'recordType':'DNS_RECORD_TYPE_CNAME','requiredValue':'x.up.railway.app',
                    'status':'DNS_RECORD_STATUS_PROPAGATED','purpose':'DNS_RECORD_PURPOSE_TRAFFIC_ROUTE'}]}}
                """);

        ShopDomainDTO dto = RailwayDomainProvisioner.toDto("example.ru", node);

        assertEquals("@", dto.records().get(0).host());
        assertTrue(dto.records().get(0).ok());
        assertEquals("railway-verify=abc", dto.records().get(1).value());
    }

    @Test
    void validCertificateIsActiveAndFailedIsError() throws Exception {
        assertEquals(ShopDomainDTO.ACTIVE, RailwayDomainProvisioner.toDto("a.ru",
                json("{'status':{'verified':true,'certificateStatus':'CERTIFICATE_STATUS_TYPE_VALID','dnsRecords':[]}}"))
                .status());
        ShopDomainDTO failed = RailwayDomainProvisioner.toDto("a.ru", json(
                "{'status':{'certificateStatus':'CERTIFICATE_STATUS_TYPE_ISSUE_FAILED','certificateErrorMessage':'CAA','dnsRecords':[]}}"));
        assertEquals(ShopDomainDTO.ERROR, failed.status());
        assertTrue(failed.message().contains("CAA"));
    }
}
