package com.tinyclaw.adapters.llm;

import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decorator that retries LLM calls for transient failures.
 *
 * <p>Only retries recognised transient error types (timeout, rate-limit, network).
 * Authentication, configuration, and provider-rejected errors are never retried.</p>
 */
public class RetryingLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmGateway.class);

    private static final Set<LlmErrorType> RETRYABLE_TYPES = EnumSet.of(
        LlmErrorType.TIMEOUT,
        LlmErrorType.RATE_LIMIT,
        LlmErrorType.TRANSIENT_NETWORK
    );

    private final LlmGateway delegate;
    private final int maxAttempts;
    private final long backoffMs;

    public RetryingLlmGateway(LlmGateway delegate, TinyClawModelProperties properties) {
        this(delegate,
            properties != null ? properties.getMaxRetryAttempts() : 3,
            properties != null ? properties.getRetryBackoffMs() : 1000);
    }

    public RetryingLlmGateway(LlmGateway delegate, int maxAttempts, long backoffMs) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must not be negative");
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoffMs must not be negative");
        }
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        int attempt = 1;
        LlmException lastException = null;

        while (attempt <= maxAttempts + 1) {
            try {
                return delegate.generate(request);
            } catch (LlmException e) {
                lastException = e;
                if (!shouldRetry(e) || attempt > maxAttempts) {
                    throw e;
                }
                log.warn("LLM call failed with retryable error (attempt {}/{}): {}",
                    attempt, maxAttempts + 1, e.getErrorType());
                sleepBackoff();
            }
            attempt++;
        }

        throw lastException != null ? lastException
            : new LlmException("LLM call failed after " + (maxAttempts + 1) + " attempts", LlmErrorType.UNKNOWN);
    }

    private boolean shouldRetry(LlmException exception) {
        return RETRYABLE_TYPES.contains(exception.getErrorType());
    }

    private void sleepBackoff() {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Retry interrupted", LlmErrorType.UNKNOWN);
        }
    }
}
