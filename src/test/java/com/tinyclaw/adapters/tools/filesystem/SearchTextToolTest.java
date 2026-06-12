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

class SearchTextToolTest {

    private SearchTextTool tool;
    private ToolExecutionContext context;

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() {
        tool = new SearchTextTool(new WorkspacePathResolver(), new ObjectMapper());
        context = new ToolExecutionContext(workspace);
    }

    @Test
    void findsTextInFile() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world\nsecond line");

        ToolResult result = tool.execute(call("{\"query\":\"world\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("notes.txt:1: hello world");
    }

    @Test
    void findsMultipleMatches() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello\nworld\nhello again");

        ToolResult result = tool.execute(call("{\"query\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("notes.txt:1: hello");
        assertThat(result.output()).contains("notes.txt:3: hello again");
    }

    @Test
    void filtersByGlob() throws IOException {
        Files.writeString(workspace.resolve("a.java"), "class A {}");
        Files.writeString(workspace.resolve("b.md"), "class B");

        ToolResult result = tool.execute(call("{\"query\":\"class\",\"glob\":\"*.java\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("a.java:1: class A {}");
        assertThat(result.output()).doesNotContain("b.md");
    }

    @Test
    void simpleGlobMatchesNestedFiles() throws IOException {
        Files.createDirectory(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Nested.java"), "class Nested {}");
        Files.writeString(workspace.resolve("root.md"), "class Root");

        ToolResult result = tool.execute(call("{\"query\":\"class\",\"glob\":\"*.java\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("src/Nested.java:1: class Nested {}");
        assertThat(result.output()).doesNotContain("root.md");
    }

    @Test
    void restrictsToBaseDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/readme.md"), "readme content");
        Files.writeString(workspace.resolve("root.txt"), "root content");

        ToolResult result = tool.execute(call("{\"query\":\"content\",\"path\":\"docs\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("docs/readme.md:1: readme content");
        assertThat(result.output()).doesNotContain("root.txt");
    }

    @Test
    void noMatchesReturnsClearMessage() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolResult result = tool.execute(call("{\"query\":\"missing\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).isEqualTo("No matches found.");
    }

    @Test
    void missingQueryReturnsFailure() {
        ToolResult result = tool.execute(call("{}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("query");
    }

    @Test
    void emptyQueryReturnsFailure() {
        ToolResult result = tool.execute(call("{\"query\":\"\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("query");
    }

    @Test
    void invalidQueryTypeReturnsFailure() {
        ToolResult result = tool.execute(call("{\"query\":123}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("query");
    }

    @Test
    void outsideWorkspaceReturnsFailure() {
        ToolResult result = tool.execute(call("{\"query\":\"x\",\"path\":\"../outside\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("escapes workspace");
    }

    @Test
    void absolutePathReturnsFailure() {
        String path = workspace.resolve("sub").toString().replace("\\", "\\\\");

        ToolResult result = tool.execute(call("{\"query\":\"x\",\"path\":\"" + path + "\"}"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Absolute paths");
    }

    @Test
    void binaryFilesAreSkipped() throws IOException {
        Files.writeString(workspace.resolve("text.txt"), "hello");
        Path binary = workspace.resolve("binary.bin");
        Files.write(binary, new byte[]{'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'});

        ToolResult result = tool.execute(call("{\"query\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("text.txt");
        assertThat(result.output()).doesNotContain("binary.bin");
    }

    @Test
    void truncatesLongOutput() throws IOException {
        String padding = "x".repeat(200);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < SearchTextTool.MAX_RESULTS; i++) {
            content.append("match line ").append(i).append(" ").append(padding).append("\n");
        }
        Files.writeString(workspace.resolve("big.txt"), content.toString());

        ToolResult result = tool.execute(call("{\"query\":\"match\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).hasSizeLessThanOrEqualTo(
            SearchTextTool.MAX_OUTPUT_CHARS + SearchTextTool.TRUNCATED_SUFFIX.length()
        );
        assertThat(result.output()).endsWith(SearchTextTool.TRUNCATED_SUFFIX);
    }

    @Test
    void limitsResultCount() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < SearchTextTool.MAX_RESULTS + 50; i++) {
            content.append("match ").append(i).append("\n");
        }
        Files.writeString(workspace.resolve("big.txt"), content.toString());

        ToolResult result = tool.execute(call("{\"query\":\"match\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains(SearchTextTool.RESULT_LIMIT_SUFFIX);
    }

    @Test
    void symlinkEscapeIsIgnored() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-search", ".txt");
        Files.writeString(outside, "secret hello");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call("{\"query\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).doesNotContain("outside-search");
        assertThat(result.output()).isEqualTo("No matches found.");
    }

    @Test
    void symlinkDirectoryEscapeIsIgnored() throws IOException {
        Path outsideDir = Files.createTempDirectory(workspace.getParent(), "outside-search-dir");
        Files.writeString(outsideDir.resolve("secret.txt"), "secret hello");
        Path linkDir = workspace.resolve("link-search-dir");
        assumeTrue(tryCreateSymbolicLink(linkDir, outsideDir), "Cannot create symbolic link on this system");

        ToolResult result = tool.execute(call("{\"query\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).doesNotContain("secret.txt");
        assertThat(result.output()).isEqualTo("No matches found.");
    }

    @Test
    void binaryDetectionSamplesOnly() throws IOException {
        Path binary = workspace.resolve("large-binary.bin");
        byte[] content = new byte[2 * 1024 * 1024];
        content[content.length - 1] = 0;
        Files.write(binary, content);

        Files.writeString(workspace.resolve("text.txt"), "hello");

        ToolResult result = tool.execute(call("{\"query\":\"hello\"}"), context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("text.txt");
        assertThat(result.output()).doesNotContain("large-binary.bin");
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspacePathResolver resolver = new WorkspacePathResolver();

        assertThatThrownBy(() -> new SearchTextTool(null, objectMapper))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("pathResolver");
        assertThatThrownBy(() -> new SearchTextTool(resolver, null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("objectMapper");
    }

    private ToolCall call(String argumentsJson) {
        return ToolCall.of("call-1", SearchTextTool.NAME, argumentsJson);
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
