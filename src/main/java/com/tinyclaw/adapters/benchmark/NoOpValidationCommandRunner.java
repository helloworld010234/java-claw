package com.tinyclaw.adapters.benchmark;

import com.tinyclaw.ports.benchmark.GoTestResult;
import com.tinyclaw.ports.benchmark.ValidationCommandRunnerPort;

import java.nio.file.Path;

/**
 * No-op validation runner used as a safe default when no real runner is wired.
 *
 * <p>Always reports SKIPPED so benchmark execution can proceed without requiring
 * a local Go installation.</p>
 */
public class NoOpValidationCommandRunner implements ValidationCommandRunnerPort {

    @Override
    public GoTestResult runGoTest(Path workspace) {
        return GoTestResult.skipped("No validation runner configured");
    }
}
