package com.tinyclaw.adapters.benchmark;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.benchmark.GoTestResult;
import com.tinyclaw.ports.benchmark.GoTestStatus;
import com.tinyclaw.ports.benchmark.ValidationCommandRunnerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code go test} for benchmark workspaces using {@link ProcessBuilder}.
 *
 * <p>This adapter keeps all external-process machinery out of the application
 * layer. It drains stdout and stderr concurrently with bounded buffers so that
 * large test outputs do not block the subprocess. It initializes a Go module
 * only when {@code go.mod} is absent, then runs {@code go test ./...}.</p>
 */
public class ProcessBuilderValidationCommandRunner implements ValidationCommandRunnerPort {

    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderValidationCommandRunner.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 50_000;

    private final int timeoutSeconds;
    private final int maxOutputChars;

    public ProcessBuilderValidationCommandRunner() {
        this(DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public ProcessBuilderValidationCommandRunner(int timeoutSeconds, int maxOutputChars) {
        this.timeoutSeconds = DomainGuards.requirePositive(timeoutSeconds, "timeoutSeconds");
        this.maxOutputChars = DomainGuards.requirePositive(maxOutputChars, "maxOutputChars");
    }

    /**
     * Runs {@code go test ./...} in the given workspace.
     *
     * <p>If Go is not installed the result is a skip. If {@code go.mod} is missing
     * the runner first runs {@code go mod init bench}. Timeouts and non-zero exit
     * codes are reported as failures with captured output.</p>
     *
     * @param workspace the workspace directory containing the Go source files
     * @return the go-test result
     */
    @Override
    public GoTestResult runGoTest(Path workspace) {
        DomainGuards.requireNonNull(workspace, "workspace");

        if (!goExists()) {
            log.info("Go is not installed or not on PATH; skipping go test for {}", workspace);
            return GoTestResult.skipped("Go is not installed or not on PATH");
        }

        try {
            if (!Files.exists(workspace.resolve("go.mod"))) {
                ProcessResult initResult = runCommand(workspace, "go", "mod", "init", "bench");
                if (initResult.exitCode() != 0) {
                    return GoTestResult.failed(
                        "go mod init failed with exit code " + initResult.exitCode(),
                        truncate(initResult.output()));
                }
            }

            ProcessResult testResult = runCommand(workspace, "go", "test", "./...");
            String output = truncate(testResult.output());
            if (testResult.timedOut()) {
                return GoTestResult.failed("go test timed out after " + timeoutSeconds + " seconds", output);
            }
            if (testResult.exitCode() != 0) {
                return GoTestResult.failed(
                    "go test failed with exit code " + testResult.exitCode(), output);
            }
            return GoTestResult.passed(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GoTestResult.failed("go test was interrupted", e.getMessage());
        } catch (IOException e) {
            return GoTestResult.failed("go test process failed: " + e.getMessage(), e.getMessage());
        }
    }

    boolean goExists() {
        try {
            ProcessResult result = runCommand(null, "go", "version");
            return result.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    ProcessResult runCommand(Path directory, String... command)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (directory != null) {
            pb.directory(directory.toFile());
        }
        pb.redirectErrorStream(false);
        Process process = pb.start();

        ExecutorService drainExecutor = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture = drainExecutor.submit(() -> drainStream(process.getInputStream()));
        Future<String> stderrFuture = drainExecutor.submit(() -> drainStream(process.getErrorStream()));

        boolean finished = process.waitFor(this.timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            String partial = collectPartialOutput(stdoutFuture, stderrFuture);
            drainExecutor.shutdownNow();
            return new ProcessResult(-1, partial, true);
        }

        String output = collectOutput(stdoutFuture, stderrFuture);
        drainExecutor.shutdown();
        return new ProcessResult(process.exitValue(), output, false);
    }

    private String drainStream(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (sb.length() + read > maxOutputChars) {
                    int allowed = maxOutputChars - sb.length();
                    if (allowed > 0) {
                        sb.append(buffer, 0, allowed);
                    }
                    sb.append("\n...[output truncated]");
                    // Continue reading to avoid blocking the subprocess, but discard data.
                    while (reader.read(buffer) != -1) {
                        // drain
                    }
                    break;
                }
                sb.append(buffer, 0, read);
            }
        } catch (IOException e) {
            sb.append("\n[failed to read stream: ").append(e.getMessage()).append("]");
        }
        return sb.toString();
    }

    private String collectOutput(Future<String> stdoutFuture, Future<String> stderrFuture) {
        try {
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            return combine(stdout, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[interrupted while reading output]";
        } catch (ExecutionException e) {
            return "[failed to read output: " + e.getCause().getMessage() + "]";
        }
    }

    private String collectPartialOutput(Future<String> stdoutFuture, Future<String> stderrFuture) {
        String stdout = readFutureSilently(stdoutFuture);
        String stderr = readFutureSilently(stderrFuture);
        return combine(stdout, stderr);
    }

    private String readFutureSilently(Future<String> future) {
        if (future.isCancelled()) {
            return "";
        }
        try {
            return future.isDone() ? future.get() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String combine(String stdout, String stderr) {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) {
            sb.append(stdout);
        }
        if (stderr != null && !stderr.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("--- stderr ---\n").append(stderr);
        }
        return sb.toString();
    }

    private String truncate(String output) {
        if (output == null || output.length() <= maxOutputChars) {
            return output;
        }
        return output.substring(0, maxOutputChars) + "\n...[output truncated]";
    }

    record ProcessResult(int exitCode, String output, boolean timedOut) {
    }
}
