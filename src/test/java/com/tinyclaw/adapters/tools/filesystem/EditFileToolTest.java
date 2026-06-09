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

class EditFileToolTest {

    private EditFileTool tool;
    private ToolExecutionContext context;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        tool = new EditFileTool(new WorkspacePathResolver(), new ObjectMapper());
        context = new ToolExecutionContext(workspace);
    }

    @Test
    void replacesUniqueOccurrenceSuccessfully() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"world\",\"newText\":\"universe\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).isEqualTo("Edited file: notes.txt");
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello universe");
    }

    @Test
    void replacesWithEmptyStringDeletesText() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\" world\",\"newText\":\"\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello");
    }

    @Test
    void missingPathReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("path");
    }

    @Test
    void missingOldTextReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("oldText");
    }

    @Test
    void missingNewTextReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"a\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("newText");
    }

    @Test
    void emptyOldTextReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("oldText");
    }

    @Test
    void pathNotStringReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":123,\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("path");
    }

    @Test
    void oldTextNotStringReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":123,\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("oldText");
    }

    @Test
    void newTextNotStringReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"a\",\"newText\":123}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("newText");
    }

    @Test
    void invalidJsonReturnsFailure() {
        ToolResult result = tool.execute(call("{"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Invalid arguments JSON");
    }

    @Test
    void fileNotFoundReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"missing.txt\",\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("cannot be accessed");
    }

    @Test
    void directoryTargetReturnsFailure() throws IOException {
        Files.createDirectory(workspace.resolve("docs"));

        ToolResult result = tool.execute(
            call("{\"path\":\"docs\",\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("directory");
    }

    @Test
    void absolutePathReturnsFailure() {
        String path = workspace.resolve("notes.txt").toString().replace("\\", "\\\\");

        ToolResult result = tool.execute(
            call("{\"path\":\"" + path + "\",\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Absolute paths");
    }

    @Test
    void outsideWorkspaceReturnsFailure() {
        ToolResult result = tool.execute(
            call("{\"path\":\"../outside.txt\",\"oldText\":\"a\",\"newText\":\"b\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void oldTextNotFoundReturnsFailureAndLeavesFileUnchanged() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"missing\",\"newText\":\"replacement\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("not found");
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello world");
    }

    @Test
    void oldTextAppearsMultipleTimesReturnsFailureAndLeavesFileUnchanged() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello hello hello");

        ToolResult result = tool.execute(
            call("{\"path\":\"notes.txt\",\"oldText\":\"hello\",\"newText\":\"hi\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("multiple times");
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello hello hello");
    }

    @Test
    void symlinkEscapeReturnsFailure() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-edit", ".txt");
        Files.writeString(outside, "secret content");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(
            call("{\"path\":\"link.txt\",\"oldText\":\"secret\",\"newText\":\"hacked\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
        assertThat(Files.readString(outside)).isEqualTo("secret content");
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver resolver = new WorkspacePathResolver();

        assertThatThrownBy(() -> new EditFileTool(null, objectMapper))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("pathResolver");
        assertThatThrownBy(() -> new EditFileTool(resolver, null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("objectMapper");
    }

    private ToolCall call(String argumentsJson) {
        return ToolCall.of("call-1", EditFileTool.NAME, argumentsJson);
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
