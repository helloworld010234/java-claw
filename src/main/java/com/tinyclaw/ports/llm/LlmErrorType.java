package com.tinyclaw.ports.llm;

/**
 * Categorised error types for LLM gateway failures.
 *
 * <p>Used to decide whether a failed request should be retried and how it should be reported.</p>
 */
public enum LlmErrorType {
    CONFIGURATION,
    AUTHENTICATION,
    TIMEOUT,
    RATE_LIMIT,
    TRANSIENT_NETWORK,
    PROVIDER_REJECTED_REQUEST,
    UNKNOWN
}
