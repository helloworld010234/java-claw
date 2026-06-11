package com.tinyclaw.adapters.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemWorkspaceGuideLoaderTest {

    private final FilesystemWorkspaceGuideLoader loader = new FilesystemWorkspaceGuideLoader();

    @Test
    void loadsExistingGuideFile(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "# Guide\nHello");
        Optional<String> result = loader.loadGuide(workspace.toString());
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("# Guide\nHello");
    }

    @Test
    void returnsEmptyWhenGuideMissing(@TempDir Path workspace) {
        Optional<String> result = loader.loadGuide(workspace.toString());
        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenGuideIsDirectory(@TempDir Path workspace) throws Exception {
        Files.createDirectory(workspace.resolve("AGENTS.md"));
        Optional<String> result = loader.loadGuide(workspace.toString());
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsNullWorkspace() {
        assertThatThrownBy(() -> loader.loadGuide(null))
            .isInstanceOf(com.tinyclaw.domain.common.TinyClawDomainException.class)
            .hasMessageContaining("workspaceRoot");
    }

    @Test
    void rejectsBlankWorkspace() {
        assertThatThrownBy(() -> loader.loadGuide("  "))
            .isInstanceOf(com.tinyclaw.domain.common.TinyClawDomainException.class)
            .hasMessageContaining("workspaceRoot");
    }
}
