package com.tinyclaw.application.benchmark;

import com.tinyclaw.domain.common.DomainGuards;

/**
 * Outcome of running {@code go test} in a benchmark workspace.
 *
 * @param status PASSED, FAILED, or SKIPPED
 * @param reason human-readable explanation for FAILED or SKIPPED
 * @param output captured stdout/stderr (may be truncated)
 */
public record GoTestResult(GoTestStatus status, String reason, String output) {

    public GoTestResult {
        DomainGuards.requireNonNull(status, "status");
    }

    public static GoTestResult passed(String output) {
        return new GoTestResult(GoTestStatus.PASSED, null, output);
    }

    public static GoTestResult failed(String reason, String output) {
        DomainGuards.requireNonBlank(reason, "reason");
        return new GoTestResult(GoTestStatus.FAILED, reason, output);
    }

    public static GoTestResult skipped(String reason) {
        DomainGuards.requireNonBlank(reason, "reason");
        return new GoTestResult(GoTestStatus.SKIPPED, reason, null);
    }

    public boolean passed() {
        return status == GoTestStatus.PASSED;
    }

    public boolean skipped() {
        return status == GoTestStatus.SKIPPED;
    }
}
