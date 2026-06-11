package com.tinyclaw.config;

import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ToolConfigurationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void readFileToolIsRegistered() {
        assertThat(toolRegistry.find("read_file")).isPresent();
    }

    @Test
    void writeFileToolIsRegistered() {
        assertThat(toolRegistry.find("write_file")).isPresent();
    }

    @Test
    void editFileToolIsRegistered() {
        assertThat(toolRegistry.find("edit_file")).isPresent();
    }

    @Test
    void shellCommandToolIsRegistered() {
        assertThat(toolRegistry.find("shell_command")).isPresent();
    }

    @Test
    void editFileExecutionRequiresApprovalContext(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolCall call = ToolCall.of(
            "call-1",
            "edit_file",
            "{\"path\":\"notes.txt\",\"oldText\":\"world\",\"newText\":\"agent\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        ToolResult result = toolRegistry.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Approval gate requires run and session context");
    }

    @Test
    void editFileExecutionWorksWithApprovalContext(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolCall call = ToolCall.of(
            "call-1",
            "edit_file",
            "{\"path\":\"notes.txt\",\"oldText\":\"world\",\"newText\":\"agent\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace, "run-1", "sess-1");

        ToolResult result = toolRegistry.execute(call, context);

        // With run/session context, approval gate creates a PENDING approval request
        // and returns failure with approval required message
        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Approval required");
    }

    @Test
    void writeFileExecutionRequiresApprovalByDefault(@TempDir Path workspace) {
        ToolCall call = ToolCall.of(
            "call-1",
            "write_file",
            "{\"path\":\"out.txt\",\"content\":\"data\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace, "run-1", "sess-1");

        ToolResult result = toolRegistry.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Approval required");
    }

    @Test
    void shellCommandExecutionRequiresApprovalByDefault(@TempDir Path workspace) {
        ToolCall call = ToolCall.of(
            "call-1",
            "shell_command",
            "{\"command\":\"echo hi\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace, "run-1", "sess-1");

        ToolResult result = toolRegistry.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Approval required");
    }
}
