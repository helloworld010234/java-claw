package com.tinyclaw.application.benchmark;

import com.tinyclaw.domain.common.DomainGuards;

import java.nio.file.Path;

/**
 * Result of executing a single benchmark case.
 *
 * @param caseId      the benchmark case identifier
 * @param status      PASSED or FAILED
 * @param runId       the agent run identifier (null if run was not started)
 * @param sessionId   the session identifier (null if session was not created)
 * @param workspace   the isolated workspace path
 * @param turnCount   number of agent turns consumed
 * @param errorReason failure reason when status is FAILED
 */
public record BenchmarkResult(
        String caseId,
        BenchmarkStatus status,
        String runId,
        String sessionId,
        Path workspace,
        int turnCount,
        String errorReason) {

    public BenchmarkResult {
        DomainGuards.requireNonBlank(caseId, "caseId");
        DomainGuards.requireNonNull(status, "status");
        DomainGuards.requireNonNull(workspace, "workspace");
        DomainGuards.requireNonNegative(turnCount, "turnCount");
    }

    /**
     * Returns true if the benchmark case passed.
     */
    public boolean passed() {
        return status == BenchmarkStatus.PASSED;
    }
}
