package ru.marketplace.cdek;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdekAuthServiceTest {

    private static final CdekCredentials SHOP_A = new CdekCredentials("client", "se+cr&et=");
    private static final CdekCredentials SHOP_B = new CdekCredentials("other", "secret");

    private final CdekWebClientStub stub = new CdekWebClientStub();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-01T09:00:00Z"));
    private final CdekAuthService auth = new CdekAuthService(stub.webClient, clock);

    @Test
    void requestsTokenWithUrlEncodedFormAndCachesItUntilExpiry() {
        stub.respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"token_type\":\"bearer\",\"expires_in\":3600}")
                .respond(HttpStatus.OK, "{\"access_token\":\"tok-2\",\"expires_in\":3600}");

        assertEquals("tok-1", auth.getAccessToken(SHOP_A));
        assertEquals("tok-1", auth.getAccessToken(SHOP_A));
        assertEquals(1, stub.requests().size());

        CdekWebClientStub.Recorded request = stub.lastRequest();
        assertEquals("https://api.cdek.test/v2/oauth/token", request.request().url().toString());
        assertTrue(request.writtenHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED));
        assertEquals("grant_type=client_credentials&client_id=client&client_secret=se%2Bcr%26et%3D", request.body());

        // За минуту до истечения токен уже считается протухшим
        clock.advance(Duration.ofSeconds(3600 - 60));
        assertEquals("tok-2", auth.getAccessToken(SHOP_A));
        assertEquals(2, stub.requests().size());
    }

    @Test
    void cachesTokenPerShopKeys() {
        stub.respond(HttpStatus.OK, "{\"access_token\":\"tok-a\",\"expires_in\":3600}")
                .respond(HttpStatus.OK, "{\"access_token\":\"tok-b\",\"expires_in\":3600}")
                .respond(HttpStatus.OK, "{\"access_token\":\"tok-a2\",\"expires_in\":3600}");

        assertEquals("tok-a", auth.getAccessToken(SHOP_A));
        assertEquals("tok-b", auth.getAccessToken(SHOP_B));
        assertEquals("tok-a", auth.getAccessToken(SHOP_A));
        assertEquals("tok-b", auth.getAccessToken(SHOP_B));
        // Мерчант сменил Secure password — токен старых ключей не переиспользуем
        assertEquals("tok-a2", auth.getAccessToken(new CdekCredentials("client", "new-secret")));
        assertEquals(3, stub.requests().size());
    }

    @Test
    void errorResponseGivesNullAndNextCallRetries() {
        stub.respond(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_client\"}")
                .respond(HttpStatus.OK, "{\"error\":\"no token here\"}")
                .respond(HttpStatus.OK, "{\"access_token\":\"tok\"}");

        assertNull(auth.getAccessToken(SHOP_A));
        assertNull(auth.getAccessToken(SHOP_A));
        assertEquals("tok", auth.getAccessToken(SHOP_A));
        assertEquals(3, stub.requests().size());
    }

    @Test
    void checkTellsRejectedKeysFromUnavailableCdek() {
        stub.respond(HttpStatus.OK, "{\"access_token\":\"tok\",\"expires_in\":3600}")
                .respond(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_client\"}")
                .respond(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_request\"}")
                .respond(HttpStatus.BAD_GATEWAY, "")
                .respond(HttpStatus.OK, "{\"error\":\"no token here\"}");

        assertEquals(CdekAuthService.Check.OK, auth.check(SHOP_A));
        assertEquals(CdekAuthService.Check.REJECTED, auth.check(SHOP_B));
        assertEquals(CdekAuthService.Check.REJECTED, auth.check(SHOP_B));
        assertEquals(CdekAuthService.Check.UNAVAILABLE, auth.check(SHOP_B));
        assertEquals(CdekAuthService.Check.UNAVAILABLE, auth.check(SHOP_B));
        // Токен, полученный при проверке, сразу годится для расчёта доставки
        assertEquals("tok", auth.getAccessToken(SHOP_A));
        assertEquals(5, stub.requests().size());
    }

    @Test
    void blankKeysAreNotCredentials() {
        assertThrows(IllegalStateException.class, () -> new CdekCredentials("", "secret"));
        assertThrows(IllegalStateException.class, () -> new CdekCredentials("client", ""));
        assertThrows(IllegalStateException.class, () -> new CdekCredentials(null, "secret"));
    }

    @Test
    void credentialsNeverPrintSecret() {
        assertEquals("CdekCredentials[clientId=client, clientSecret=***]", SHOP_A.toString());
    }

    /** Часы, которые тест двигает вперёд, — проверить истечение токена без sleep */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
