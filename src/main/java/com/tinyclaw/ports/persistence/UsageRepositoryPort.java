package com.tinyclaw.ports.persistence;

import com.tinyclaw.application.persistence.UsageRecord;

/**
 * Port for persisting per-LLM-call usage and cost records.
 */
public interface UsageRepositoryPort {

    /**
     * Save a usage record. Implementations must be idempotent-friendly
     * (same call may be recorded more than once during retries).
     */
    void save(UsageRecord record);
}
