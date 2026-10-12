package ru.marketplace.cdek;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import ru.marketplace.cdek.dto.CdekPvzDTO;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdekDeliveryPointsHttpGatewayTest {

    private static final String POINTS = """
            [
              {"code":"MSK1","name":"На Тверской","work_time":"Пн-Вс 10:00-21:00","type":"PVZ",
               "location":{"country_code":"RU","region":"Москва","city":"Москва","postal_code":"101000",
                           "address":"ул. Тверская, 1","address_full":"Россия, Москва, ул. Тверская, 1",
                           "longitude":37.61,"latitude":55.75}},
              {"name":"без кода","location":{"city":"Тула"}},
              null,
              {"code":"ALA1","location":{"country_code":"KZ","city":"Алматы","postal_code":null,"longitude":"76.9"}},
              {"code":"NOLOC"}
            ]
            """;

    private static final CdekCredentials KEYS = new CdekCredentials("client", "secret");

    private final CdekAuthService auth = mock(CdekAuthService.class);
    private final CdekWebClientStub stub = new CdekWebClientStub();
    private final CdekDeliveryPointsHttpGateway gateway = new CdekDeliveryPointsHttpGateway(auth, stub.webClient);

    @Test
    void parsesPointsSkippingBrokenOnes() throws Exception {
        List<CdekPvzDTO> points = CdekDeliveryPointsHttpGateway.parse(POINTS.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("MSK1", "ALA1", "NOLOC"), points.stream().map(CdekPvzDTO::getCode).toList());
        CdekPvzDTO msk = points.get(0);
        assertEquals("На Тверской", msk.getName());
        assertEquals("Пн-Вс 10:00-21:00", msk.getWorkTime());
        assertEquals("RU", msk.getCountryCode());
        assertEquals("Москва", msk.getRegion());
        assertEquals("101000", msk.getPostalCode());
        assertEquals("ул. Тверская, 1", msk.getAddress());
        assertEquals("Россия, Москва, ул. Тверская, 1", msk.getFullAddress());
        assertEquals(37.61, msk.getLongitude());
        assertEquals(55.75, msk.getLatitude());

        CdekPvzDTO ala = points.get(1);
        assertNull(ala.getPostalCode());
        assertEquals(76.9, ala.getLongitude());
        assertNull(ala.getLatitude());
        assertNull(points.get(2).getCity());
    }

    @Test
    void fetchesPvzListWithToken() {
        when(auth.getAccessToken(KEYS)).thenReturn("tok");
        stub.respond(HttpStatus.OK, POINTS);

        List<CdekPvzDTO> points = gateway.fetchAllPickupPoints(KEYS);

        assertEquals(3, points.size());
        CdekWebClientStub.Recorded request = stub.lastRequest();
        assertEquals("https://api.cdek.test/v2/deliverypoints?type=PVZ", request.request().url().toString());
        assertEquals("Bearer tok", request.request().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void failuresGiveEmptyListSoCacheIsKept() {
        when(auth.getAccessToken(KEYS)).thenReturn(null);
        assertTrue(gateway.fetchAllPickupPoints(KEYS).isEmpty());
        assertTrue(stub.requests().isEmpty());

        when(auth.getAccessToken(KEYS)).thenReturn("tok");
        stub.respond(HttpStatus.INTERNAL_SERVER_ERROR, "{}")
                .respond(HttpStatus.OK, "{\"not\":\"array\"}");
        assertTrue(gateway.fetchAllPickupPoints(KEYS).isEmpty());
        assertTrue(gateway.fetchAllPickupPoints(KEYS).isEmpty());
    }
}
