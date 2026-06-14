package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.feishu.dto.FeishuTenantAccessTokenRequest;
import com.tinyclaw.adapters.web.feishu.dto.FeishuTenantAccessTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe cached provider for Feishu tenant access tokens.
 *
 * <p>Fetches the token via {@code /open-apis/auth/v3/tenant_access_token/internal}
 * and caches it until {@code expire - tokenRefreshSkewSeconds}. Concurrent callers
 * block on a single refresh; after the token is cached, subsequent callers read it
 * without contention.</p>
 */
public class FeishuTenantAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(FeishuTenantAccessTokenProvider.class);

    private static final String TOKEN_ENDPOINT = "/open-apis/auth/v3/tenant_access_token/internal";

    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final long tokenRefreshSkewSeconds;
    private final FeishuHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.MIN;

    /**
     * Creates the provider.
     *
     * @param baseUrl                 Feishu OpenAPI base URL, e.g. {@code https://open.feishu.cn}
     * @param appId                   Feishu app ID
     * @param appSecret               Feishu app secret
     * @param tokenRefreshSkewSeconds refresh the token this many seconds before it expires
     * @param transport               HTTP transport
     * @param objectMapper            JSON mapper
     */
    public FeishuTenantAccessTokenProvider(String baseUrl,
                                           String appId,
                                           String appSecret,
                                           long tokenRefreshSkewSeconds,
                                           FeishuHttpTransport transport,
                                           ObjectMapper objectMapper) {
        this(baseUrl, appId, appSecret, tokenRefreshSkewSeconds, transport, objectMapper, Clock.systemUTC());
    }

    /**
     * Creates the provider with a pluggable clock (intended for tests).
     *
     * @param baseUrl                 Feishu OpenAPI base URL
     * @param appId                   Feishu app ID
     * @param appSecret               Feishu app secret
     * @param tokenRefreshSkewSeconds refresh the token this many seconds before it expires
     * @param transport               HTTP transport
     * @param objectMapper            JSON mapper
     * @param clock                   clock used to decide token expiry
     */
    FeishuTenantAccessTokenProvider(String baseUrl,
                                    String appId,
                                    String appSecret,
                                    long tokenRefreshSkewSeconds,
                                    FeishuHttpTransport transport,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        if (tokenRefreshSkewSeconds < 0) {
            throw new IllegalArgumentException("tokenRefreshSkewSeconds must not be negative");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        String normalizedBaseUrl = baseUrl != null ? baseUrl : "";
        this.baseUrl = normalizedBaseUrl.endsWith("/")
            ? normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1)
            : normalizedBaseUrl;
        this.appId = appId != null ? appId : "";
        this.appSecret = appSecret != null ? appSecret : "";
        this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Returns a valid tenant access token, fetching or refreshing it if necessary.
     *
     * @return non-blank tenant access token
     * @throws FeishuApiException if the token cannot be obtained
     */
    public String getToken() {
        if (isCachedTokenValid()) {
            log.debug("[FeishuTokenProvider] Reusing cached tenant_access_token");
            return cachedToken;
        }
        lock.lock();
        try {
            if (isCachedTokenValid()) {
                return cachedToken;
            }
            return fetchToken();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the cached token so the next {@link #getToken()} call fetches a new one.
     * Safe to call from the sender when a token error is detected.
     */
    public void invalidate() {
        lock.lock();
        try {
            log.debug("[FeishuTokenProvider] Token cache invalidated");
            cachedToken = null;
            expiresAt = Instant.MIN;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCachedTokenValid() {
        String token = cachedToken;
        Instant expiry = expiresAt;
        if (token == null || token.isBlank()) {
            return false;
        }
        Instant refreshDeadline = expiry.minusSeconds(tokenRefreshSkewSeconds);
        return clock.instant().isBefore(refreshDeadline);
    }

    private String fetchToken() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (appId.isBlank()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        if (appSecret.isBlank()) {
            throw new IllegalArgumentException("appSecret must not be blank");
        }
        String uri = baseUrl + TOKEN_ENDPOINT;
        FeishuTenantAccessTokenRequest request = new FeishuTenantAccessTokenRequest(appId, appSecret);
        log.debug("[FeishuTokenProvider] Fetching tenant_access_token from {}", uri);
        String responseBody = transport.post(uri, Map.of("Content-Type", "application/json"), request);
        FeishuTenantAccessTokenResponse response = parseResponse(responseBody);
        if (response.code() != 0) {
            throw new FeishuApiException(
                "Failed to obtain Feishu tenant_access_token: code=" + response.code() + ", msg=" + response.msg(),
                response.code()
            );
        }
        if (response.tenantAccessToken() == null || response.tenantAccessToken().isBlank()) {
            throw new FeishuApiException("Feishu tenant_access_token missing in response");
        }
        long effectiveExpire = Math.max(response.expire(), 0);
        cachedToken = response.tenantAccessToken();
        expiresAt = clock.instant().plusSeconds(effectiveExpire);
        log.debug("[FeishuTokenProvider] tenant_access_token obtained, expires in {} seconds", effectiveExpire);
        return cachedToken;
    }

    private FeishuTenantAccessTokenResponse parseResponse(String body) {
        try {
            return objectMapper.readValue(body, FeishuTenantAccessTokenResponse.class);
        } catch (JsonProcessingException e) {
            throw new FeishuApiException("Failed to parse Feishu token response: " + e.getMessage(), e);
        }
    }
}
