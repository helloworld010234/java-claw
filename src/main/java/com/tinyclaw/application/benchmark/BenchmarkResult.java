package com.tinyclaw.application.benchmark;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;

import java.nio.file.Path;

/**
 * Result of executing a single benchmark case.
 *
 * @param caseId           the benchmark case identifier
 * @param status           PASSED or FAILED
 * @param runId            the agent run identifier (null if run was not started)
 * @param sessionId        the session identifier (null if session was not created)
 * @param workspace        the isolated workspace path
 * @param turnCount        number of agent turns consumed
 * @param errorReason      failure reason when status is FAILED
 * @param durationMillis   elapsed wall-clock time for setup + execution + validation
 * @param usage            aggregated LLM token usage when available
 * @param validationOutput captured validation summary or assertion output
 * @param goTestOutput     captured {@code go test} output when executed
 */
public record BenchmarkResult(
        String caseId,
        BenchmarkStatus status,
        String runId,
        String sessionId,
        Path workspace,
        int turnCount,
        String errorReason,
        Long durationMillis,
        Usage usage,
        String validationOutput,
        String goTestOutput) {

    public BenchmarkResult {
        DomainGuards.requireNonBlank(caseId, "caseId");
        DomainGuards.requireNonNull(status, "status");
        DomainGuards.requireNonNull(workspace, "workspace");
        DomainGuards.requireNonNegative(turnCount, "turnCount");
    }

    /**
     * Backward-compatible constructor for callers that do not track
     * duration, usage, or captured output.
     */
    public BenchmarkResult(String caseId,
                           BenchmarkStatus status,
                           String runId,
                           String sessionId,
                           Path workspace,
                           int turnCount,
                           String errorReason) {
        this(caseId, status, runId, sessionId, workspace, turnCount, errorReason,
             null, null, null, null);
    }

    /**
     * Returns true if the benchmark case passed.
     */
    public boolean passed() {
        return status == BenchmarkStatus.PASSED;
    }
}
