package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;

import java.util.List;

/**
 * No-op implementation of {@link UsageRepositoryPort}.
 * Used as default when no persistence is required.
 */
public class NoOpUsageRepository implements UsageRepositoryPort {

    @Override
    public void save(UsageRecord record) {
        // intentionally empty
    }

    @Override
    public List<UsageRecord> findByRunId(String runId) {
        return List.of();
    }
}
