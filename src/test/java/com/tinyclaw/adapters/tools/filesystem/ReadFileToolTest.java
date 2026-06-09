package com.tinyclaw.adapters.tools.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReadFileToolTest {

    private ReadFileTool tool;
    private ToolExecutionContext context;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        tool = new ReadFileTool(new WorkspacePathResolver(), new ObjectMapper());
        context = new ToolExecutionContext(workspace);
    }

    @Test
    void readsFileInsideWorkspace() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello");

        ToolResult result = tool.execute(call("{\"path\":\"notes.txt\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).isEqualTo("hello");
    }

    @Test
    void fileNotFoundReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"missing.txt\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("cannot be accessed");
    }

    @Test
    void directoryPathReturnsFailure() throws IOException {
        Files.createDirectory(workspace.resolve("docs"));

        ToolResult result = tool.execute(call("{\"path\":\"docs\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("directory");
    }

    @Test
    void outsideWorkspaceReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"../outside.txt\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void missingPathArgumentReturnsFailure() {
        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("path");
    }

    @Test
    void definitionIsValid() {
        assertThat(tool.name()).isEqualTo(ReadFileTool.NAME);
        assertThat(tool.definition().name()).isEqualTo(ReadFileTool.NAME);
        assertThat(tool.definition().inputSchemaJson()).contains("\"path\"");
    }

    @Test
    void symlinkEscapeReturnsFailure() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-read", ".txt");
        Files.writeString(outside, "secret");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call("{\"path\":\"link.txt\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver resolver = new WorkspacePathResolver();

        assertThatThrownBy(() -> new ReadFileTool(null, objectMapper))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("pathResolver");
        assertThatThrownBy(() -> new ReadFileTool(resolver, null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("objectMapper");
    }

    private ToolCall call(String argumentsJson) {
        return ToolCall.of("call-1", ReadFileTool.NAME, argumentsJson);
    }

    private boolean tryCreateSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return false;
        }
    }
}
