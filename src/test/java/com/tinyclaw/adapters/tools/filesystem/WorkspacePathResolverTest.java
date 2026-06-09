package com.tinyclaw.adapters.tools.filesystem;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspacePathResolverTest {

    private final WorkspacePathResolver resolver = new WorkspacePathResolver();

    @TempDir
    Path workspace;

    @Test
    void resolvesNormalRelativePath() {
        Path resolved = resolver.resolve(workspace, "notes/todo.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("notes/todo.txt").toAbsolutePath().normalize());
    }

    @Test
    void blankPathThrows() {
        assertThatThrownBy(() -> resolver.resolve(workspace, " "))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("userPath");
    }

    @Test
    void absolutePathThrows() {
        assertThatThrownBy(() -> resolver.resolve(workspace, workspace.resolve("file.txt").toString()))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("Absolute paths");
    }

    @Test
    void dotDotEscapeThrows() {
        assertThatThrownBy(() -> resolver.resolve(workspace, "../outside.txt"))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("escapes workspace");
    }

    @Test
    void normalizeInsideWorkspaceSuccess() {
        Path resolved = resolver.resolve(workspace, "notes/../todo.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("todo.txt").toAbsolutePath().normalize());
    }

    @Test
    void resolveExistingAllowsRegularWorkspaceFile() throws IOException {
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "hello");

        Path resolved = resolver.resolveExisting(workspace, "a.txt");

        assertThat(resolved).isEqualTo(file.toRealPath());
    }

    @Test
    void resolveExistingRejectsSymlinkEscape() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside", ".txt");
        Files.writeString(outside, "secret");
        Path link = workspace.resolve("link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        assertThatThrownBy(() -> resolver.resolveExisting(workspace, "link.txt"))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("escapes workspace");
    }

    @Test
    void resolveWritableAllowsNewFileInsideWorkspace() throws IOException {
        Path resolved = resolver.resolveWritable(workspace, "new.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("new.txt").toAbsolutePath().normalize());
    }

    @Test
    void resolveWritableAllowsExistingRegularFileInsideWorkspace() throws IOException {
        Path file = workspace.resolve("existing.txt");
        Files.writeString(file, "old");

        Path resolved = resolver.resolveWritable(workspace, "existing.txt");

        assertThat(resolved).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void resolveWritableRejectsSymlinkParentEscape() throws IOException {
        Path outsideDir = Files.createTempDirectory(workspace.getParent(), "outside-dir");
        Path linkDir = workspace.resolve("link-dir");
        assumeTrue(tryCreateSymbolicLink(linkDir, outsideDir), "Cannot create symbolic link on this system");

        assertThatThrownBy(() -> resolver.resolveWritable(workspace, "link-dir/file.txt"))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("escapes workspace");
    }

    @Test
    void resolveWritableRejectsExistingSymlinkTargetEscape() throws IOException {
        Path outside = Files.createTempFile(workspace.getParent(), "outside-target", ".txt");
        Path link = workspace.resolve("target-link.txt");
        assumeTrue(tryCreateSymbolicLink(link, outside), "Cannot create symbolic link on this system");

        assertThatThrownBy(() -> resolver.resolveWritable(workspace, "target-link.txt"))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("escapes workspace");
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
