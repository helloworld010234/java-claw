package com.tinyclaw.adapters.tools.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands within the workspace using the JDK standard {@link ProcessBuilder}.
 *
 * <p>Windows: uses {@code powershell -NoProfile -NonInteractive -Command}.
 * Non-Windows: uses {@code sh -c}.</p>
 *
 * <p>Uses a background reader thread to consume process output concurrently,
 * preventing pipe-buffer deadlock on large outputs.</p>
 */
@Component
public class ShellCommandTool implements AgentTool {

    public static final String NAME = "shell_command";
    private static final String DESCRIPTION = "Execute a short shell command in the workspace. Returns stdout and stderr.";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "The shell command to execute"
            }
          },
          "required": ["command"]
        }
        """;

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int MAX_OUTPUT_CHARS = 8000;
    static final String TRUNCATED_SUFFIX = "\n...[Output truncated to " + MAX_OUTPUT_CHARS + " chars]";

    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public ShellCommandTool() {
        this(new ObjectMapper(), DEFAULT_TIMEOUT_SECONDS);
    }

    public ShellCommandTool(ObjectMapper objectMapper, int timeoutSeconds) {
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
        this.timeoutSeconds = DomainGuards.requireNonNegative(timeoutSeconds, "timeoutSeconds");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME, DESCRIPTION, INPUT_SCHEMA_JSON);
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        String command;
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());
            JsonNode commandNode = root.get("command");
            if (commandNode == null || !commandNode.isTextual()) {
                return ToolResult.failure(call.id(), "Missing or invalid 'command' argument");
            }
            command = commandNode.asText();
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Invalid arguments JSON: " + e.getMessage());
        }

        if (command.isBlank()) {
            return ToolResult.failure(call.id(), "Command is empty or blank");
        }

        ProcessBuilder pb = createProcessBuilder(command);
        pb.directory(context.workspaceRoot().toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Failed to start process: " + e.getMessage());
        }

        OutputCollector collector = new OutputCollector(process.getInputStream());
        Thread readerThread = new Thread(collector);
        readerThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            awaitReader(readerThread);
            return ToolResult.failure(call.id(), "Command interrupted");
        }

        if (!finished) {
            process.destroyForcibly();
            awaitReader(readerThread);
            String output = collector.output();
            if (collector.isTruncated()) {
                output = output + TRUNCATED_SUFFIX;
            }
            output = output + "\n[Command timed out after " + timeoutSeconds + " seconds]";
            return ToolResult.failure(call.id(), output);
        }

        awaitReader(readerThread);

        int exitCode = process.exitValue();
        String output = collector.output();
        if (collector.isTruncated()) {
            output = output + TRUNCATED_SUFFIX;
        }

        if (exitCode != 0) {
            return ToolResult.failure(call.id(), "Exit code: " + exitCode + "\n" + output);
        }

        return ToolResult.success(call.id(), output);
    }

    private ProcessBuilder createProcessBuilder(String command) {
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", command);
        }
        return new ProcessBuilder("sh", "-c", command);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    static String truncate(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATED_SUFFIX;
    }

    private void awaitReader(Thread readerThread) {
        try {
            readerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Concurrently reads process output into a bounded buffer, continuing to
     * drain the stream after the bound is reached to prevent pipe deadlock.
     */
    private static class OutputCollector implements Runnable {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder(MAX_OUTPUT_CHARS + TRUNCATED_SUFFIX.length());
        private volatile boolean truncated = false;

        OutputCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = stream.read(buf)) != -1) {
                    if (!truncated) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        buffer.append(chunk);
                        if (buffer.length() > MAX_OUTPUT_CHARS) {
                            buffer.setLength(MAX_OUTPUT_CHARS);
                            truncated = true;
                        }
                    }
                    // Continue reading to drain pipe and prevent child deadlock
                }
            } catch (IOException e) {
                // Stream closed by process termination, ignore
            }
        }

        String output() {
            return buffer.toString();
        }

        boolean isTruncated() {
            return truncated;
        }
    }
}
