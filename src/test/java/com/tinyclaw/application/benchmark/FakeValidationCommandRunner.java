package com.tinyclaw.application.benchmark;

import com.tinyclaw.ports.benchmark.GoTestResult;
import com.tinyclaw.ports.benchmark.ValidationCommandRunnerPort;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fake {@link ValidationCommandRunnerPort} for tests.
 */
public class FakeValidationCommandRunner implements ValidationCommandRunnerPort {

    private final Deque<GoTestResult> results = new ArrayDeque<>();

    public FakeValidationCommandRunner(GoTestResult... results) {
        for (GoTestResult result : results) {
            this.results.add(result);
        }
    }

    public static FakeValidationCommandRunner skipped(String reason) {
        return new FakeValidationCommandRunner(GoTestResult.skipped(reason));
    }

    public static FakeValidationCommandRunner passed(String output) {
        return new FakeValidationCommandRunner(GoTestResult.passed(output));
    }

    public static FakeValidationCommandRunner failed(String reason, String output) {
        return new FakeValidationCommandRunner(GoTestResult.failed(reason, output));
    }

    @Override
    public GoTestResult runGoTest(Path workspace) {
        return results.isEmpty() ? GoTestResult.skipped("fake empty") : results.pollFirst();
    }
}
