package com.tinyclaw.application.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GoTestExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsWhenGoIsNotInstalled() {
        GoTestExecutor executor = new GoTestExecutor(5, 1_000) {
            @Override
            boolean goExists() {
                return false;
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).containsIgnoringCase("go");
    }

    @Test
    void runsGoTestAndPassesForValidCode() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        GoTestExecutor executor = new GoTestExecutor(30, 10_000) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                if (command.length == 4 && "mod".equals(command[1]) && "init".equals(command[2])) {
                    try {
                        Files.writeString(directory.resolve("go.mod"), "module bench\n");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new ProcessResult(0, "", false);
                }
                return new ProcessResult(0, "ok\n", false);
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.output()).contains("ok");
        assertThat(Files.exists(tempDir.resolve("go.mod"))).isTrue();
    }

    @Test
    void doesNotInitModuleWhenGoModAlreadyExists() throws IOException {
        Files.writeString(tempDir.resolve("go.mod"), "module bench\n");
        GoTestExecutor executor = new GoTestExecutor(30, 10_000) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                return new ProcessResult(0, "ok\n", false);
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void capturesFailureForFailingTest() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        GoTestExecutor executor = new GoTestExecutor(30, 10_000) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                if (command.length > 0 && "go".equals(command[0]) && command.length > 1 && "test".equals(command[1])) {
                    return new ProcessResult(1, "--- FAIL: TestMultiply\nforced failure", false);
                }
                return new ProcessResult(0, "", false);
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("exit code");
        assertThat(result.output()).contains("forced failure");
    }

    @Test
    void reportsTimeoutWithPartialOutput() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        GoTestExecutor executor = new GoTestExecutor(1, 1_000) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                if (command.length > 0 && "go".equals(command[0]) && command.length > 1 && "test".equals(command[1])) {
                    return new ProcessResult(-1, "partial output...", true);
                }
                return new ProcessResult(0, "", false);
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("timed out");
        assertThat(result.output()).contains("partial output");
    }

    @Test
    void boundedOutputTruncatesLargeOutput() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        GoTestExecutor executor = new GoTestExecutor(30, 20) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                if (command.length > 0 && "go".equals(command[0]) && command.length > 1 && "test".equals(command[1])) {
                    return new ProcessResult(0, "a".repeat(1_000), false);
                }
                return new ProcessResult(0, "", false);
            }
        };

        GoTestResult result = executor.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.output()).hasSizeLessThan(100);
        assertThat(result.output()).contains("truncated");
    }

    private void writeString(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }
}
