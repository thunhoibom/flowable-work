package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OauthClientCredentialsTest {

    @Test
    void reusesATokenUntilThirtySecondsBeforeExpiry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        var oauth = new OauthClientCredentials(request -> {
            calls.incrementAndGet();
            assertEquals(true, request.body().contains("client_secret=s3cret"));
            assertEquals(true, request.body().contains("scope=read"));
            return new TransportResponse(200, "{\"access_token\":\"tok\",\"expires_in\":90}");
        }, clock);
        assertEquals("tok", oauth.accessToken(operation()));
        assertEquals("tok", oauth.accessToken(operation()));
        assertEquals(1, calls.get());
        clock.advance(60);
        assertEquals("tok", oauth.accessToken(operation()));
        assertEquals(2, calls.get());
    }

    @Test
    void tokenFailureIsAnIoFailure() {
        var oauth = new OauthClientCredentials(
                request -> new TransportResponse(401, "{\"error\":\"invalid_client\"}"),
                Clock.systemUTC());
        assertThrows(IOException.class, () -> oauth.accessToken(operation()));
    }

    private static PublishedOperation operation() {
        return new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.OAUTH2_CLIENT, null, "s3cret", null,
                1000, 1000, List.of(), List.of(), List.of(), List.of(),
                ContentType.JSON, null, List.of(), BodyPresence.NONE, ApiKeyLocation.HEADER,
                new AuthDetails("https://auth.example/token", "crm", List.of("read"), null, null, null, null, null, null));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(long seconds) {
            now = now.plusSeconds(seconds);
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
