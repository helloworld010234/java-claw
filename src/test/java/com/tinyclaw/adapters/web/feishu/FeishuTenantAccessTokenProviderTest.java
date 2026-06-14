package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuTenantAccessTokenProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://open.feishu.cn";
    private static final String APP_ID = "app-id";
    private static final String APP_SECRET = "app-secret";
    private static final long SKEW = 300;

    @Test
    void firstCallFetchesToken() {
        FakeTransport transport = new FakeTransport(
            tokenResponse("token-1", 7200),
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);

        String token = provider.getToken();

        assertThat(token).isEqualTo("token-1");
        assertThat(transport.tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void secondCallReusesTokenBeforeExpiry() {
        FakeTransport transport = new FakeTransport(
            tokenResponse("token-1", 7200),
            Map.of()
        );
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FeishuTenantAccessTokenProvider provider = createProvider(transport, clock);

        provider.getToken();
        clock.advanceSeconds(100);
        String token = provider.getToken();

        assertThat(token).isEqualTo("token-1");
        assertThat(transport.tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void tokenIsRefreshedWhenCloseToExpiry() {
        FakeTransport transport = new FakeTransport(
            List.of(tokenResponse("token-1", 600), tokenResponse("token-2", 600)),
            Map.of()
        );
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FeishuTenantAccessTokenProvider provider = createProvider(transport, clock);

        provider.getToken();
        clock.advanceSeconds(350);
        String token = provider.getToken();

        assertThat(token).isEqualTo("token-2");
        assertThat(transport.tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void concurrentCallsDoNotRequestManyTokens() throws InterruptedException {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        FakeTransport transport = new FakeTransport(
            tokenResponse("token-concurrent", 7200),
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    provider.getToken();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(transport.tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void feishuErrorThrowsSafely() {
        FakeTransport transport = new FakeTransport(
            errorTokenResponse(99991663, "tenant_access_token invalid"),
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);

        assertThatThrownBy(provider::getToken)
            .isInstanceOf(FeishuApiException.class)
            .satisfies(e -> assertThat(((FeishuApiException) e).getCode()).isEqualTo(99991663))
            .hasMessageContaining("code=99991663");
    }

    @Test
    void missingTokenInResponseThrowsSafely() {
        FakeTransport transport = new FakeTransport(
            "{\"code\":0,\"msg\":\"ok\"}",
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);

        assertThatThrownBy(provider::getToken)
            .isInstanceOf(FeishuApiException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void invalidBaseUrlRejected() {
        FakeTransport transport = new FakeTransport("", Map.of());
        FeishuTenantAccessTokenProvider provider = new FeishuTenantAccessTokenProvider(
            "  ", APP_ID, APP_SECRET, SKEW, transport, OBJECT_MAPPER
        );
        assertThatThrownBy(provider::getToken)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("baseUrl");
    }

    @Test
    void missingAppIdRejected() {
        FakeTransport transport = new FakeTransport("", Map.of());
        FeishuTenantAccessTokenProvider provider = new FeishuTenantAccessTokenProvider(
            BASE_URL, "  ", APP_SECRET, SKEW, transport, OBJECT_MAPPER
        );
        assertThatThrownBy(provider::getToken)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("appId");
    }

    @Test
    void missingAppSecretRejected() {
        FakeTransport transport = new FakeTransport("", Map.of());
        FeishuTenantAccessTokenProvider provider = new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, null, SKEW, transport, OBJECT_MAPPER
        );
        assertThatThrownBy(provider::getToken)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("appSecret");
    }

    @Test
    void negativeExpireIsTreatedAsZero() {
        FakeTransport transport = new FakeTransport(
            List.of(tokenResponse("token-1", -10), tokenResponse("token-1", -10)),
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);

        String token = provider.getToken();

        assertThat(token).isEqualTo("token-1");
        // token with expire <= 0 is immediately stale -> next call fetches again
        String token2 = provider.getToken();
        assertThat(token2).isEqualTo("token-1");
        assertThat(transport.tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void invalidateForcesRefresh() {
        FakeTransport transport = new FakeTransport(
            List.of(tokenResponse("token-1", 7200), tokenResponse("token-2", 7200)),
            Map.of()
        );
        FeishuTenantAccessTokenProvider provider = createProvider(transport);

        assertThat(provider.getToken()).isEqualTo("token-1");
        provider.invalidate();
        assertThat(provider.getToken()).isEqualTo("token-2");
        assertThat(transport.tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void publicConstructorUsesSystemClock() {
        FakeTransport transport = new FakeTransport(tokenResponse("token-1", 7200), Map.of());
        FeishuTenantAccessTokenProvider provider = new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, APP_SECRET, SKEW, transport, OBJECT_MAPPER
        );
        assertThat(provider.getToken()).isEqualTo("token-1");
    }

    @Test
    void nullDependenciesRejected() {
        FakeTransport transport = new FakeTransport("", Map.of());
        assertThatThrownBy(() -> new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, APP_SECRET, SKEW, null, OBJECT_MAPPER
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, APP_SECRET, SKEW, transport, null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, APP_SECRET, -1, transport, OBJECT_MAPPER
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private FeishuTenantAccessTokenProvider createProvider(FakeTransport transport) {
        return createProvider(transport, Clock.systemUTC());
    }

    private FeishuTenantAccessTokenProvider createProvider(FakeTransport transport, Clock clock) {
        return new FeishuTenantAccessTokenProvider(
            BASE_URL, APP_ID, APP_SECRET, SKEW, transport, OBJECT_MAPPER, clock
        );
    }

    private static String tokenResponse(String token, int expire) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                "code", 0,
                "msg", "ok",
                "tenant_access_token", token,
                "expire", expire
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String errorTokenResponse(int code, String msg) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("code", code, "msg", msg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class FakeTransport implements FeishuHttpTransport {

        private final List<String> tokenResponseBodies;
        private final Map<String, String> sendMessageResponses;
        final AtomicInteger tokenCalls = new AtomicInteger();
        final List<RecordedCall> recordedCalls = new ArrayList<>();

        FakeTransport(String tokenResponseBody, Map<String, String> sendMessageResponses) {
            this(List.of(tokenResponseBody), sendMessageResponses);
        }

        FakeTransport(List<String> tokenResponseBodies, Map<String, String> sendMessageResponses) {
            this.tokenResponseBodies = new ArrayList<>(tokenResponseBodies);
            this.sendMessageResponses = sendMessageResponses;
        }

        @Override
        public String post(String uri, Map<String, String> headers, Object body) {
            recordedCalls.add(new RecordedCall(uri, headers, body));
            if (uri.contains("tenant_access_token/internal")) {
                tokenCalls.incrementAndGet();
                if (tokenResponseBodies.isEmpty()) {
                    throw new FeishuApiException("no more token responses");
                }
                return tokenResponseBodies.removeFirst();
            }
            return sendMessageResponses.getOrDefault(uri, "{\"code\":0,\"msg\":\"ok\"}");
        }
    }

    private record RecordedCall(String uri, Map<String, String> headers, Object body) {
    }

    private static class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this.instant = instant;
            this.zone = ZoneId.of("UTC");
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
