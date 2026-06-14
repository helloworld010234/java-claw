package com.tinyclaw.adapters.benchmark;

import com.tinyclaw.ports.benchmark.GoTestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBuilderValidationCommandRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsWhenGoIsNotInstalled() {
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(5, 1_000) {
            @Override
            boolean goExists() {
                return false;
            }
        };

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).containsIgnoringCase("go");
    }

    @Test
    void runsGoTestAndPassesForValidCode() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(30, 10_000) {
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

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.output()).contains("ok");
        assertThat(Files.exists(tempDir.resolve("go.mod"))).isTrue();
    }

    @Test
    void doesNotInitModuleWhenGoModAlreadyExists() throws IOException {
        Files.writeString(tempDir.resolve("go.mod"), "module bench\n");
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(30, 10_000) {
            @Override
            boolean goExists() {
                return true;
            }

            @Override
            ProcessResult runCommand(Path directory, String... command) {
                return new ProcessResult(0, "ok\n", false);
            }
        };

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void capturesFailureForFailingTest() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(30, 10_000) {
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

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("exit code");
        assertThat(result.output()).contains("forced failure");
    }

    @Test
    void reportsTimeoutWithPartialOutput() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(1, 1_000) {
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

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("timed out");
        assertThat(result.output()).contains("partial output");
    }

    @Test
    void boundedOutputTruncatesLargeOutput() throws IOException {
        writeString(tempDir.resolve("math.go"), "package math\n");
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(30, 20) {
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

        GoTestResult result = runner.runGoTest(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.output()).hasSizeLessThan(100);
        assertThat(result.output()).contains("truncated");
    }

    @Test
    void realRunCommandCombinesStdoutAndStderr() throws Exception {
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(5, 1_000);

        ProcessBuilderValidationCommandRunner.ProcessResult result = runner.runCommand(
            null, javaExecutable(), "-cp", javaClasspath(), StreamPrinter.class.getName(), "both"
        );

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).contains("stdout-line");
        assertThat(result.output()).contains("--- stderr ---");
        assertThat(result.output()).contains("stderr-line");
    }

    @Test
    void realRunCommandTruncatesLargeStreamsWhileDraining() throws Exception {
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(5, 40);

        ProcessBuilderValidationCommandRunner.ProcessResult result = runner.runCommand(
            null, javaExecutable(), "-cp", javaClasspath(), StreamPrinter.class.getName(), "large"
        );

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("...[output truncated]");
    }

    @Test
    void realRunCommandReportsTimeout() throws Exception {
        ProcessBuilderValidationCommandRunner runner = new ProcessBuilderValidationCommandRunner(1, 1_000);

        ProcessBuilderValidationCommandRunner.ProcessResult result = runner.runCommand(
            null, javaExecutable(), "-cp", javaClasspath(), StreamPrinter.class.getName(), "sleep"
        );

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isTrue();
    }

    private void writeString(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static String javaClasspath() {
        return System.getProperty("java.class.path");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static class StreamPrinter {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "both" -> {
                    System.out.print("stdout-line");
                    System.err.print("stderr-line");
                }
                case "large" -> System.out.print("x".repeat(2_000));
                case "sleep" -> {
                    Thread.sleep(3_000);
                    System.out.print("late");
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
