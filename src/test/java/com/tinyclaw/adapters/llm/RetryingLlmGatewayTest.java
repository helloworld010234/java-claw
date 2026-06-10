package com.tinyclaw.adapters.llm;

import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingLlmGatewayTest {

    private final LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());

    @Test
    void successOnFirstAttempt() {
        LlmGateway delegate = req -> new LlmResponse("ok", List.of(), null);
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 2, 0);

        LlmResponse response = gateway.generate(request);
        assertThat(response.content()).isEqualTo("ok");
    }

    @Test
    void retriesTimeoutUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("timed out", LlmErrorType.TIMEOUT);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 2, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.TIMEOUT));

        assertThat(calls.get()).isEqualTo(3); // initial + 2 retries
    }

    @Test
    void retriesTransientNetworkUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("network error", LlmErrorType.TRANSIENT_NETWORK);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 1, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retriesRateLimitUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("rate limited", LlmErrorType.RATE_LIMIT);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 1, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void recoversAfterRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            if (calls.incrementAndGet() < 3) {
                throw new LlmException("timeout", LlmErrorType.TIMEOUT);
            }
            return new LlmResponse("ok", List.of(), null);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 3, 0);

        LlmResponse response = gateway.generate(request);
        assertThat(response.content()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryAuthentication() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("auth failed", LlmErrorType.AUTHENTICATION);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 3, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.AUTHENTICATION));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryConfiguration() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("bad config", LlmErrorType.CONFIGURATION);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 3, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryProviderRejected() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("bad request", LlmErrorType.PROVIDER_REJECTED_REQUEST);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 3, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryUnknown() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("unknown", LlmErrorType.UNKNOWN);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 3, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void zeroMaxAttemptsMeansNoRetry() {
        AtomicInteger calls = new AtomicInteger(0);
        LlmGateway delegate = req -> {
            calls.incrementAndGet();
            throw new LlmException("timeout", LlmErrorType.TIMEOUT);
        };
        RetryingLlmGateway gateway = new RetryingLlmGateway(delegate, 0, 0);

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new RetryingLlmGateway(null, 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delegate");
    }

    @Test
    void constructorRejectsNegativeMaxAttempts() {
        assertThatThrownBy(() -> new RetryingLlmGateway(req -> null, -1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxAttempts");
    }

    @Test
    void constructorRejectsNegativeBackoff() {
        assertThatThrownBy(() -> new RetryingLlmGateway(req -> null, 0, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("backoffMs");
    }
}
