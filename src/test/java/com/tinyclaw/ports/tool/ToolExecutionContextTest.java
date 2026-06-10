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

    @Test
    void withApprovedApprovalAddsApprovalId() {
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "run-1", "sess-1")
            .withApprovedApproval("apr-1");

        assertThat(context.approvedApprovalId()).isEqualTo("apr-1");
        assertThat(context.runId()).isEqualTo("run-1");
        assertThat(context.sessionId()).isEqualTo("sess-1");
    }

    @Test
    void withRunPreservesApprovedApprovalId() {
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."))
            .withApprovedApproval("apr-1")
            .withRun("run-1", "sess-1");

        assertThat(context.approvedApprovalId()).isEqualTo("apr-1");
        assertThat(context.runId()).isEqualTo("run-1");
        assertThat(context.sessionId()).isEqualTo("sess-1");
    }

    @Test
    void legacyConstructorLeavesApprovedApprovalIdNull() {
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."), "run-1", "sess-1");

        assertThat(context.approvedApprovalId()).isNull();
    }
}
