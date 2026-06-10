package com.tinyclaw.adapters.llm;

import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutLlmGatewayTest {

    private final LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());

    @Test
    void returnsResponseWhenWithinTimeout() {
        LlmGateway delegate = req -> new LlmResponse("ok", List.of(), null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TimeoutLlmGateway gateway = new TimeoutLlmGateway(delegate, 5, executor);
            LlmResponse response = gateway.generate(request);
            assertThat(response.content()).isEqualTo("ok");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutConvertsToLlmException() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        LlmGateway blockingGateway = req -> {
            try {
                latch.await(); // blocks until test releases
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmResponse("never", List.of(), null);
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TimeoutLlmGateway gateway = new TimeoutLlmGateway(blockingGateway, 1, executor);
            assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.TIMEOUT))
                .hasMessageContaining("timed out");
        } finally {
            latch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rethrowsLlmExceptionFromDelegate() {
        LlmGateway failingGateway = req -> {
            throw new LlmException("boom", LlmErrorType.PROVIDER_REJECTED_REQUEST);
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TimeoutLlmGateway gateway = new TimeoutLlmGateway(failingGateway, 5, executor);
            assertThatThrownBy(() -> gateway.generate(request))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.PROVIDER_REJECTED_REQUEST));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new TimeoutLlmGateway(null, 1, Executors.newSingleThreadExecutor()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delegate");
    }

    @Test
    void constructorRejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new TimeoutLlmGateway(req -> null, 0, Executors.newSingleThreadExecutor()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeoutSeconds");
    }

    @Test
    void constructorRejectsNullExecutor() {
        assertThatThrownBy(() -> new TimeoutLlmGateway(req -> null, 1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("executor");
    }
}
