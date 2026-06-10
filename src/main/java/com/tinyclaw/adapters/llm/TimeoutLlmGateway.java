package com.tinyclaw.adapters.llm;
import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Decorator that enforces a caller-visible timeout on every LLM call.
 *
 * <p>If the delegate does not complete within the configured duration,
 * the caller receives a typed {@link LlmException} with {@link LlmErrorType#TIMEOUT}.
 * Whether the underlying request is actually interrupted still depends on the
 * delegate and its HTTP client honoring interruption or their own transport-level timeouts.</p>
 */
public class TimeoutLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    private final long timeoutSeconds;
    private final ExecutorService executor;

    public TimeoutLlmGateway(LlmGateway delegate, long timeoutSeconds, ExecutorService executor) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.delegate = delegate;
        this.timeoutSeconds = timeoutSeconds;
        this.executor = executor;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        try {
            return CompletableFuture
                .supplyAsync(() -> delegate.generate(request), executor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new LlmException(
                    "LLM request timed out after " + timeoutSeconds + "s",
                    LlmErrorType.TIMEOUT
                );
            }
            if (cause instanceof LlmException le) {
                throw le;
            }
            throw LlmErrorClassifier.classify(cause);
        }
    }
}
