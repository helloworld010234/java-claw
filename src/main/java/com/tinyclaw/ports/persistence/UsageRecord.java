package com.tinyclaw.ports.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * DTO for persisting a single LLM call's token usage and estimated cost.
 *
 * @param runId            the agent run id
 * @param sessionId        the session id
 * @param model            the model name
 * @param promptTokens     prompt tokens consumed
 * @param completionTokens completion tokens consumed
 * @param estimatedCostCny estimated cost in CNY (null if pricing not configured)
 * @param success          true if the LLM call succeeded
 * @param recordedAt       timestamp of recording
 */
public record UsageRecord(
    String runId,
    String sessionId,
    String model,
    int promptTokens,
    int completionTokens,
    Double estimatedCostCny,
    boolean success,
    Instant recordedAt
) {

    public UsageRecord {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must not be negative");
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("completionTokens must not be negative");
        }
    }
}
