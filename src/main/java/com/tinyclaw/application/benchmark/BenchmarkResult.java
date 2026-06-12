package com.tinyclaw.application.benchmark;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.benchmark.GoTestStatus;

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
 * @param durationMillis   elapsed wall-clock time for setup + execution + validation + go-test
 * @param usage            aggregated LLM token usage when available
 * @param validationOutput captured validation summary or assertion output
 * @param goTestOutput     captured {@code go test} output when executed
 * @param goTestStatus     explicit status of the optional {@code go test} invocation
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
        String goTestOutput,
        GoTestStatus goTestStatus) {

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
             null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for callers that tracked captured output
     * before the explicit {@link GoTestStatus} field was introduced.
     *
     * <p>The go-test status is derived from the captured output for callers that
     * have not yet migrated to the explicit status field. New code should prefer
     * the canonical constructor and supply the status explicitly.</p>
     */
    public BenchmarkResult(String caseId,
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
        this(caseId, status, runId, sessionId, workspace, turnCount, errorReason,
             durationMillis, usage, validationOutput, goTestOutput,
             deriveGoTestStatus(goTestOutput, status));
    }

    /**
     * Returns true if the benchmark case passed.
     */
    public boolean passed() {
        return status == BenchmarkStatus.PASSED;
    }

    /**
     * Returns the explicit go-test status when a validation runner was invoked.
     *
     * @return PASSED, FAILED, SKIPPED, or {@code null} if go test was not executed
     */
    @Override
    public GoTestStatus goTestStatus() {
        return goTestStatus;
    }

    private static GoTestStatus deriveGoTestStatus(String goTestOutput, BenchmarkStatus status) {
        if (goTestOutput == null) {
            return null;
        }
        if (goTestOutput.startsWith("Skipped:")) {
            return GoTestStatus.SKIPPED;
        }
        return status == BenchmarkStatus.PASSED ? GoTestStatus.PASSED : GoTestStatus.FAILED;
    }
}
