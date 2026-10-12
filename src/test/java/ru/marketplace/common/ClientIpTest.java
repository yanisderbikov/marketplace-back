package ru.marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpTest {

    @Test
    void lastForwardedAddressIsTheOneAppendedByEdgeProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        // Клиент прислал свой X-Forwarded-For, прокси дописал реальный адрес в конец
        request.addHeader("X-Forwarded-For", " 1.2.3.4 , 203.0.113.7 ");

        assertEquals("203.0.113.7", ClientIp.of(request));
    }

    @Test
    void spoofedLeftPartDoesNotChangeTheKey() {
        MockHttpServletRequest a = new MockHttpServletRequest();
        a.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7");
        MockHttpServletRequest b = new MockHttpServletRequest();
        b.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8, 203.0.113.7");

        assertEquals(ClientIp.of(a), ClientIp.of(b));
    }

    @Test
    void separateHeaderLinesUseTheLastLine() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertEquals("203.0.113.7", ClientIp.of(request));
    }

    @Test
    void fallsBackToRemoteAddrWithoutUsableHeader() {
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.1");
        assertEquals("198.51.100.1", ClientIp.of(direct));

        // Пустой последний элемент — не повод верить клиентским элементам левее
        MockHttpServletRequest blankLast = new MockHttpServletRequest();
        blankLast.setRemoteAddr("198.51.100.1");
        blankLast.addHeader("X-Forwarded-For", "203.0.113.7, ");
        assertEquals("198.51.100.1", ClientIp.of(blankLast));

        MockHttpServletRequest blankHeader = new MockHttpServletRequest();
        blankHeader.setRemoteAddr("198.51.100.1");
        blankHeader.addHeader("X-Forwarded-For", "  ");
        assertEquals("198.51.100.1", ClientIp.of(blankHeader));
    }

    @Test
    void garbageForwardedHeaderIsTruncated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "x".repeat(500));

        assertEquals(ClientIp.MAX_IP_LENGTH, ClientIp.of(request).length());
    }
}
