package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionContextTest {

    @Test
    void normalizesWorkspaceRoot() {
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."));

        assertThat(context.workspaceRoot()).isAbsolute();
        assertThat(context.workspaceRoot()).isNormalized();
    }

    @Test
    void nullWorkspaceRootThrows() {
        assertThatThrownBy(() -> new ToolExecutionContext(null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("workspaceRoot");
    }
}
