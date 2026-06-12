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

class WriteFileToolTest {

    private WriteFileTool tool;
    private ToolExecutionContext context;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        tool = new WriteFileTool(new WorkspacePathResolver(), new ObjectMapper());
        context = new ToolExecutionContext(workspace);
    }

    @Test
    void writesNewFileInsideWorkspace() throws IOException {
        ToolResult result = tool.execute(call("{\"path\":\"notes.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello");
    }

    @Test
    void missingPathReturnsFailure() {
        ToolResult result = tool.execute(call("{\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("path");
    }

    @Test
    void missingContentReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"notes.txt\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("content");
    }

    @Test
    void invalidOverwriteTypeReturnsFailure() {
        ToolResult result = tool.execute(call(
            "{\"path\":\"notes.txt\",\"content\":\"hello\",\"overwrite\":\"yes\"}"
        ), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("overwrite");
    }

    @Test
    void outsideWorkspaceReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"../outside.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void absolutePathReturnsFailure() {
        String path = workspace.resolve("notes.txt").toString().replace("\\", "\\\\");

        ToolResult result = tool.execute(call("{\"path\":\"" + path + "\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Absolute paths");
    }

    @Test
    void parentDirectoryMissingCreatesDirectories() throws IOException {
        ToolResult result = tool.execute(call("{\"path\":\"missing/notes.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("missing/notes.txt"))).isEqualTo("hello");
    }

    @Test
    void parentCreationStaysInsideWorkspace() {
        ToolResult result = tool.execute(call("{\"path\":\"../outside/notes.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void parentPathIsFileReturnsFailure() throws IOException {
        Files.writeString(workspace.resolve("parent"), "not a directory");

        ToolResult result = tool.execute(call("{\"path\":\"parent/file.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
    }

    @Test
    void existingFileWithoutOverwriteReturnsFailure() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "old");

        ToolResult result = tool.execute(call("{\"path\":\"notes.txt\",\"content\":\"new\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("old");
    }

    @Test
    void existingFileWithOverwriteTrueReplacesContent() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "old");

        ToolResult result = tool.execute(call(
            "{\"path\":\"notes.txt\",\"content\":\"new\",\"overwrite\":true}"
        ), context);

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("new");
    }

    @Test
    void directoryTargetReturnsFailure() throws IOException {
        Files.createDirectory(workspace.resolve("docs"));

        ToolResult result = tool.execute(call("{\"path\":\"docs\",\"content\":\"hello\",\"overwrite\":true}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("directory");
    }

    @Test
    void invalidJsonReturnsFailure() {
        ToolResult result = tool.execute(call("{"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Invalid arguments JSON");
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver resolver = new WorkspacePathResolver();

        assertThatThrownBy(() -> new WriteFileTool(null, objectMapper))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("pathResolver");
        assertThatThrownBy(() -> new WriteFileTool(resolver, null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("objectMapper");
    }

    @Test
    void symlinkParentEscapeReturnsFailure() throws IOException {
        Path outsideDir = Files.createTempDirectory(workspace.getParent(), "outside-write-dir");
        Path linkDir = workspace.resolve("link-dir");
        assumeTrue(tryCreateSymbolicLink(linkDir, outsideDir), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call("{\"path\":\"link-dir/file.txt\",\"content\":\"hello\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void overwriteSymlinkTargetOutsideWorkspaceReturnsFailure() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-write", ".txt");
        Files.writeString(outside, "secret");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call(
            "{\"path\":\"link.txt\",\"content\":\"new\",\"overwrite\":true}"
        ), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
        assertThat(Files.readString(outside)).isEqualTo("secret");
    }

    private ToolCall call(String argumentsJson) {
        return ToolCall.of("call-1", WriteFileTool.NAME, argumentsJson);
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
