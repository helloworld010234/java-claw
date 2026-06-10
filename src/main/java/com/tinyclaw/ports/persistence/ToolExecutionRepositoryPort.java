package com.tinyclaw.ports.persistence;

import com.tinyclaw.application.persistence.ToolExecutionRecord;

import java.util.List;

/**
 * Port for persisting and retrieving tool execution records for audit.
 */
public interface ToolExecutionRepositoryPort {

    /**
     * Append a tool execution record for a run.
     */
    void append(String runId, ToolExecutionRecord record);

    /**
     * Find all tool executions for a run, ordered by creation time.
     */
    List<ToolExecutionRecord> findByRunId(String runId);
}
