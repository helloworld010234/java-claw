package com.tinyclaw.config;

import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.tool.ToolApprovalRequiredException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ToolConfigurationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolRegistry readOnlyToolRegistry;

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
    void globFilesToolIsRegistered() {
        assertThat(toolRegistry.find("glob_files")).isPresent();
    }

    @Test
    void searchTextToolIsRegistered() {
        assertThat(toolRegistry.find("search_text")).isPresent();
    }

    @Test
    void readOnlyRegistryContainsReadTools() {
        assertThat(readOnlyToolRegistry.find("read_file")).isPresent();
        assertThat(readOnlyToolRegistry.find("glob_files")).isPresent();
        assertThat(readOnlyToolRegistry.find("search_text")).isPresent();
    }

    @Test
    void readOnlyRegistryExcludesWriteEditAndShellTools() {
        assertThat(readOnlyToolRegistry.find("write_file")).isEmpty();
        assertThat(readOnlyToolRegistry.find("edit_file")).isEmpty();
        assertThat(readOnlyToolRegistry.find("shell_command")).isEmpty();
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

        // Without run/session context the approval gate denies the call rather than
        // creating a pending approval request.
        ToolResult result = toolRegistry.execute(call, context);
        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("run and session context");
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

        assertThatThrownBy(() -> toolRegistry.execute(call, context))
            .isInstanceOf(ToolApprovalRequiredException.class)
            .satisfies(ex -> {
                ToolApprovalRequiredException approvalEx = (ToolApprovalRequiredException) ex;
                assertThat(approvalEx.approvalId()).isNotBlank();
                assertThat(approvalEx.toolCall().name()).isEqualTo("edit_file");
            });
    }

    @Test
    void writeFileExecutionRequiresApprovalByDefault(@TempDir Path workspace) {
        ToolCall call = ToolCall.of(
            "call-1",
            "write_file",
            "{\"path\":\"out.txt\",\"content\":\"data\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace, "run-1", "sess-1");

        assertThatThrownBy(() -> toolRegistry.execute(call, context))
            .isInstanceOf(ToolApprovalRequiredException.class)
            .satisfies(ex -> {
                ToolApprovalRequiredException approvalEx = (ToolApprovalRequiredException) ex;
                assertThat(approvalEx.approvalId()).isNotBlank();
                assertThat(approvalEx.toolCall().name()).isEqualTo("write_file");
            });
    }

    @Test
    void shellCommandExecutionRequiresApprovalByDefault(@TempDir Path workspace) {
        ToolCall call = ToolCall.of(
            "call-1",
            "shell_command",
            "{\"command\":\"echo hi\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace, "run-1", "sess-1");

        assertThatThrownBy(() -> toolRegistry.execute(call, context))
            .isInstanceOf(ToolApprovalRequiredException.class)
            .satisfies(ex -> {
                ToolApprovalRequiredException approvalEx = (ToolApprovalRequiredException) ex;
                assertThat(approvalEx.approvalId()).isNotBlank();
                assertThat(approvalEx.toolCall().name()).isEqualTo("shell_command");
            });
    }
}
