package com.tinyclaw.ports.persistence;

import com.tinyclaw.application.persistence.UsageRecord;

import java.util.List;

/**
 * Port for persisting per-LLM-call usage and cost records.
 */
public interface UsageRepositoryPort {

    /**
     * Save a usage record. Implementations must be idempotent-friendly
     * (same call may be recorded more than once during retries).
     */
    void save(UsageRecord record);

    /**
     * Find all usage records for a run, ordered by creation time.
     */
    List<UsageRecord> findByRunId(String runId);
}
