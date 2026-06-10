package com.tinyclaw.adapters.tools.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellCommandToolTest {

    @TempDir
    Path tempDir;

    private final ShellCommandTool tool = new ShellCommandTool();
    private final ToolExecutionContext context = new ToolExecutionContext(Path.of("."));

    @Test
    void successfulCommandReturnsSuccess() {
        String command = isWindows() ? "Write-Output 'hello'" : "echo hello";
        ToolCall call = ToolCall.of("c1", "shell_command", "{\"command\":\"" + command + "\"}");

        ToolResult result = tool.execute(call, new ToolExecutionContext(tempDir));

        assertThat(result.error()).isFalse();
        assertThat(result.output()).containsIgnoringCase("hello");
    }

    @Test
    void nonZeroExitCodeReturnsFailure() {
        String command = isWindows() ? "exit 1" : "false";
        ToolCall call = ToolCall.of("c1", "shell_command", "{\"command\":\"" + command + "\"}");

        ToolResult result = tool.execute(call, new ToolExecutionContext(tempDir));

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Exit code: 1");
    }

    @Test
    void emptyCommandReturnsFailure() {
        ToolCall call = ToolCall.of("c1", "shell_command", "{\"command\":\"     \"}");

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("empty or blank");
    }

    @Test
    void missingCommandArgumentReturnsFailure() {
        ToolCall call = ToolCall.of("c1", "shell_command", "{}");

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Missing or invalid 'command'");
    }

    @Test
    void commandExecutesInWorkspaceDirectory() throws Exception {
        Files.writeString(tempDir.resolve("marker.txt"), "found");
        String command = isWindows()
            ? "Get-Content marker.txt"
            : "cat marker.txt";
        ToolCall call = ToolCall.of("c1", "shell_command", "{\"command\":\"" + command + "\"}");

        ToolResult result = tool.execute(call, new ToolExecutionContext(tempDir));

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("found");
    }

    @Test
    void outputExceedingMaxCharsIsTruncated() {
        // This is tested via the static truncate method; integration testing with
        // actual shell commands is platform-dependent and slow.
        String longOutput = "a".repeat(ShellCommandTool.MAX_OUTPUT_CHARS + 10);
        String truncated = ShellCommandTool.truncate(longOutput);
        assertThat(truncated).endsWith(ShellCommandTool.TRUNCATED_SUFFIX);
        assertThat(truncated.length()).isEqualTo(ShellCommandTool.MAX_OUTPUT_CHARS + ShellCommandTool.TRUNCATED_SUFFIX.length());
    }

    @Test
    void truncateMethodTruncatesLongOutput() {
        String longOutput = "a".repeat(ShellCommandTool.MAX_OUTPUT_CHARS + 10);

        String result = ShellCommandTool.truncate(longOutput);

        assertThat(result).endsWith(ShellCommandTool.TRUNCATED_SUFFIX);
        assertThat(result.length()).isEqualTo(ShellCommandTool.MAX_OUTPUT_CHARS + ShellCommandTool.TRUNCATED_SUFFIX.length());
    }

    @Test
    void truncateMethodLeavesShortOutputUnchanged() {
        String shortOutput = "short";

        String result = ShellCommandTool.truncate(shortOutput);

        assertThat(result).isEqualTo("short");
    }

    @Test
    void truncateMethodHandlesNull() {
        assertThat(ShellCommandTool.truncate(null)).isEqualTo("");
    }

    @Test
    void definitionHasCorrectName() {
        assertThat(tool.definition().name()).isEqualTo("shell_command");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
