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

class GlobFilesToolTest {

    private GlobFilesTool tool;
    private ToolExecutionContext context;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        tool = new GlobFilesTool(new WorkspacePathResolver(), new ObjectMapper());
        context = new ToolExecutionContext(workspace);
    }

    @Test
    void listsAllFilesByDefault() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.md"), "b");
        Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub/c.txt"), "c");

        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("a.txt", "b.md", "sub/c.txt");
    }

    @Test
    void filtersByGlobPattern() throws IOException {
        Files.writeString(workspace.resolve("a.java"), "a");
        Files.writeString(workspace.resolve("b.md"), "b");
        Files.createDirectory(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/c.java"), "c");

        ToolResult result = tool.execute(call("{\"glob\":\"*.java\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("a.java", "src/c.java");
        assertThat(result.output()).doesNotContain("b.md");
    }

    @Test
    void restrictsToBaseDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "r");
        Files.writeString(workspace.resolve("root.txt"), "r");

        ToolResult result = tool.execute(call("{\"path\":\"docs\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("docs/readme.md");
        assertThat(result.output()).doesNotContain("root.txt");
    }

    @Test
    void outsideWorkspaceReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"../outside\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void absolutePathReturnsFailure() {
        String path = workspace.resolve("sub").toString().replace("\\", "\\\\");

        ToolResult result = tool.execute(call("{\"path\":\"" + path + "\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Absolute paths");
    }

    @Test
    void missingBaseDirectoryReturnsFailure() {
        ToolResult result = tool.execute(call("{\"path\":\"missing\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("does not exist");
    }

    @Test
    void baseDirectoryIsFileReturnsFailure() throws IOException {
        Files.writeString(workspace.resolve("file.txt"), "x");

        ToolResult result = tool.execute(call("{\"path\":\"file.txt\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("not a directory");
    }

    @Test
    void invalidGlobTypeReturnsFailure() {
        ToolResult result = tool.execute(call("{\"glob\":123}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("glob");
    }

    @Test
    void invalidJsonReturnsFailure() {
        ToolResult result = tool.execute(call("{"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Invalid arguments JSON");
    }

    @Test
    void truncatesLongOutput() throws IOException {
        String longName = "a".repeat(100);
        for (int i = 0; i < GlobFilesTool.MAX_RESULTS; i++) {
            Files.writeString(workspace.resolve(longName + i + ".txt"), "x");
        }

        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).hasSizeLessThanOrEqualTo(
            GlobFilesTool.MAX_OUTPUT_CHARS + GlobFilesTool.TRUNCATED_SUFFIX.length()
        );
        assertThat(result.output()).endsWith(GlobFilesTool.TRUNCATED_SUFFIX);
    }

    @Test
    void limitsResultCount() throws IOException {
        for (int i = 0; i < GlobFilesTool.MAX_RESULTS + 50; i++) {
            Files.writeString(workspace.resolve("file" + i + ".txt"), "x");
        }

        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains(GlobFilesTool.RESULT_LIMIT_SUFFIX);
    }

    @Test
    void symlinkEscapeIsIgnored() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-glob", ".txt");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).doesNotContain("outside-glob");
        assertThat(result.output()).contains("No matching files");
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver resolver = new WorkspacePathResolver();

        assertThatThrownBy(() -> new GlobFilesTool(null, objectMapper))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("pathResolver");
        assertThatThrownBy(() -> new GlobFilesTool(resolver, null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("objectMapper");
    }

    private ToolCall call(String argumentsJson) {
        return ToolCall.of("call-1", GlobFilesTool.NAME, argumentsJson);
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
