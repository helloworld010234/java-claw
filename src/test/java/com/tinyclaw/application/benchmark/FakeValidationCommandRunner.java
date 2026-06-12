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
    private final long delayMillis;

    public FakeValidationCommandRunner(GoTestResult... results) {
        this(0, results);
    }

    public FakeValidationCommandRunner(long delayMillis, GoTestResult... results) {
        this.delayMillis = delayMillis;
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

    public static FakeValidationCommandRunner withDelay(long delayMillis, GoTestResult... results) {
        return new FakeValidationCommandRunner(delayMillis, results);
    }

    @Override
    public GoTestResult runGoTest(Path workspace) {
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return results.isEmpty() ? GoTestResult.skipped("fake empty") : results.pollFirst();
    }
}
