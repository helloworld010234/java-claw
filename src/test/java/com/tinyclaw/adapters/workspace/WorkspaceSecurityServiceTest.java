package com.tinyclaw.adapters.workspace;

import com.tinyclaw.config.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceSecurityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void nullWorkDirResolvesToDefault() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        Path resolved = service.resolveWorkspace(null);

        assertThat(resolved).isEqualTo(tempDir.resolve("default").toAbsolutePath().normalize());
    }

    @Test
    void blankWorkDirResolvesToDefault() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        Path resolved = service.resolveWorkspace("   ");

        assertThat(resolved).isEqualTo(tempDir.resolve("default").toAbsolutePath().normalize());
    }

    @Test
    void allowedIdResolvesToPathUnderRoot() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default", "proj-a"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        Path resolved = service.resolveWorkspace("proj-a");

        assertThat(resolved).isEqualTo(tempDir.resolve("proj-a").toAbsolutePath().normalize());
    }

    @Test
    void allowedIdWithSubPathResolvesUnderRoot() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default", "proj-a"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        Path resolved = service.resolveWorkspace("proj-a/src");

        assertThat(resolved).isEqualTo(tempDir.resolve("proj-a/src").toAbsolutePath().normalize());
    }

    @Test
    void absolutePathIsRejected() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThatThrownBy(() -> service.resolveWorkspace("C:\\tmp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Absolute workspace paths are not allowed");
    }

    @Test
    void directoryTraversalIsRejected() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThatThrownBy(() -> service.resolveWorkspace("../etc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("directory traversal");
    }

    @Test
    void notAllowedIdIsRejected() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThatThrownBy(() -> service.resolveWorkspace("unauthorized"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Workspace id not in allowlist");
    }

    @Test
    void notAllowedIdWithSubPathIsRejected() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThatThrownBy(() -> service.resolveWorkspace("unauthorized/src"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Workspace id not in allowlist");
    }

    @Test
    void pathEscapingRootIsRejected() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default", "proj-a"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThatThrownBy(() -> service.resolveWorkspace("proj-a/../../outside"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("directory traversal");
    }

    @Test
    void rootReturnsConfiguredRoot() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThat(service.root()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    void allowedIdsReturnsConfiguredList() {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setRoot(tempDir.toString());
        props.setAllowedIds(List.of("default", "proj-a", "proj-b"));
        WorkspaceSecurityService service = new WorkspaceSecurityService(props);

        assertThat(service.allowedIds()).containsExactly("default", "proj-a", "proj-b");
    }
}
