package com.tinyclaw.application.benchmark;

import com.tinyclaw.ports.benchmark.GoTestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkResultTest {

    @TempDir
    Path tempDir;

    @Test
    void goTestStatusIsNullWhenGoTestWasNotRun() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            null,
            null
        );

        assertThat(result.goTestStatus()).isNull();
    }

    @Test
    void explicitSkippedStatusIsPreserved() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            "Skipped: Go not installed or not on PATH",
            GoTestStatus.SKIPPED
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.SKIPPED);
    }

    @Test
    void explicitPassedStatusIsPreserved() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            "ok\n",
            GoTestStatus.PASSED
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.PASSED);
    }

    @Test
    void explicitFailedStatusIsPreserved() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.FAILED,
            "run-1",
            "session-1",
            tempDir,
            3,
            "go test failed with exit code 1",
            100L,
            null,
            "Validation passed",
            "--- FAIL: TestMultiply",
            GoTestStatus.FAILED
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.FAILED);
    }

    @Test
    void backwardCompatibleConstructorDerivesSkippedFromOutputPrefix() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            "Skipped: Go not installed or not on PATH"
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.SKIPPED);
    }

    @Test
    void backwardCompatibleConstructorDerivesPassedFromOutputAndStatus() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            "ok\n"
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.PASSED);
    }

    @Test
    void backwardCompatibleConstructorDerivesFailedFromOutputAndStatus() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.FAILED,
            "run-1",
            "session-1",
            tempDir,
            3,
            "go test failed with exit code 1",
            100L,
            null,
            "Validation passed",
            "--- FAIL: TestMultiply"
        );

        assertThat(result.goTestStatus()).isEqualTo(GoTestStatus.FAILED);
    }

    @Test
    void goTestOutputDoesNotCarryStatusSemanticsWhenExplicitStatusIsNull() {
        BenchmarkResult result = new BenchmarkResult(
            "case-1",
            BenchmarkStatus.PASSED,
            "run-1",
            "session-1",
            tempDir,
            3,
            null,
            100L,
            null,
            "Validation passed",
            "some raw output without skipped prefix",
            null
        );

        assertThat(result.goTestStatus()).isNull();
    }
}
