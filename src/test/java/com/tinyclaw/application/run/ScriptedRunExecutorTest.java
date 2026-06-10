package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinyclaw.adapters.tools.command.ShellCommandTool;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedRunExecutorTest {

    @TempDir
    Path tempDir;

    private ScriptedRunExecutor executor() {
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool()
        ));
        return new ScriptedRunExecutor(registry, new ObjectMapper());
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(tempDir);
    }

    private ObjectNode args(String key, Object value) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put(key, String.valueOf(value));
        return node;
    }

    private ObjectNode argsWrite(String path, String content, boolean overwrite) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("path", path);
        node.put("content", content);
        node.put("overwrite", overwrite);
        return node;
    }

    private ObjectNode argsEdit(String path, String oldText, String newText) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("path", path);
        node.put("oldText", oldText);
        node.put("newText", newText);
        return node;
    }

    private ObjectNode argsRead(String path) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("path", path);
        return node;
    }

    @Test
    void sequentialWriteEditReadSucceeds() {
        ScriptedRunPlan plan = new ScriptedRunPlan(true, List.of(
            new ScriptedRunStep("write-notes", "write_file", argsWrite("notes.txt", "hello world", true)),
            new ScriptedRunStep("edit-notes", "edit_file", argsEdit("notes.txt", "world", "agent")),
            new ScriptedRunStep("read-notes", "read_file", argsRead("notes.txt"))
        ));

        ScriptedRunResult result = executor().execute(plan, context());

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).error()).isFalse();
        assertThat(result.steps().get(1).error()).isFalse();
        assertThat(result.steps().get(2).error()).isFalse();
        assertThat(result.steps().get(2).output()).isEqualTo("hello agent");
    }

    @Test
    void stopOnErrorTrueStopsAfterFailure() {
        ScriptedRunPlan plan = new ScriptedRunPlan(true, List.of(
            new ScriptedRunStep("missing-read", "read_file", argsRead("missing.txt")),
            new ScriptedRunStep("should-not-run", "write_file", argsWrite("should-not-run.txt", "bad", true))
        ));

        ScriptedRunResult result = executor().execute(plan, context());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).error()).isTrue();
        assertThat(Files.exists(tempDir.resolve("should-not-run.txt"))).isFalse();
    }

    @Test
    void stopOnErrorFalseContinuesAfterFailure() {
        ScriptedRunPlan plan = new ScriptedRunPlan(false, List.of(
            new ScriptedRunStep("missing-read", "read_file", argsRead("missing.txt")),
            new ScriptedRunStep("write-ok", "write_file", argsWrite("ok.txt", "ok", true))
        ));

        ScriptedRunResult result = executor().execute(plan, context());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).error()).isTrue();
        assertThat(result.steps().get(1).error()).isFalse();
    }

    @Test
    void unknownToolReturnsFailure() {
        ScriptedRunPlan plan = new ScriptedRunPlan(true, List.of(
            new ScriptedRunStep("unknown-step", "unknown", args("key", "value"))
        ));

        ScriptedRunResult result = executor().execute(plan, context());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).error()).isTrue();
        assertThat(result.steps().get(0).output()).contains("Unknown tool");
    }

    @Test
    void emptyStepsRejected() {
        assertThatThrownBy(() -> new ScriptedRunPlan(true, List.of()))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("steps must not be empty");
    }

    @Test
    void stepMissingIdRejected() {
        assertThatThrownBy(() -> new ScriptedRunStep(null, "read_file", argsRead("a.txt")))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("step id");
    }

    @Test
    void stepMissingToolRejected() {
        assertThatThrownBy(() -> new ScriptedRunStep("id", null, argsRead("a.txt")))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("step tool");
    }

    @Test
    void stepMissingArgsRejected() {
        assertThatThrownBy(() -> new ScriptedRunStep("id", "read_file", null))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("step args");
    }

    @Test
    void approvalGateBlocksShellCommandAndStepFails() {
        ToolRegistry registry = new ToolRegistry(List.of(
            new ReadFileTool(),
            new WriteFileTool(),
            new EditFileTool(),
            new ShellCommandTool()
        ), List.of(
            new ApprovalGatePolicy(new InMemoryApprovalRepository(), List.of("shell_command"), java.time.Clock.systemUTC())
        ));
        ScriptedRunExecutor executor = new ScriptedRunExecutor(registry, new ObjectMapper());

        ScriptedRunPlan plan = new ScriptedRunPlan(true, List.of(
            new ScriptedRunStep("shell-step", "shell_command", args("command", "echo hi"))
        ));

        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, "run-1", "sess-1");
        ScriptedRunResult result = executor.execute(plan, ctx, "run-1", "sess-1", null);

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).error()).isTrue();
        assertThat(result.steps().get(0).output()).contains("Approval required");
    }

    private static class InMemoryApprovalRepository implements com.tinyclaw.ports.persistence.ApprovalRepositoryPort {
        private final java.util.List<com.tinyclaw.domain.approval.ApprovalRequest> requests = new java.util.ArrayList<>();

        @Override
        public void save(com.tinyclaw.domain.approval.ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public java.util.Optional<com.tinyclaw.domain.approval.ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public java.util.Optional<com.tinyclaw.domain.approval.ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public java.util.List<com.tinyclaw.domain.approval.ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public java.util.List<com.tinyclaw.domain.approval.ApprovalRequest> findByStatus(com.tinyclaw.domain.approval.ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public java.util.List<com.tinyclaw.domain.approval.ApprovalRequest> findAll() {
            return java.util.List.copyOf(requests);
        }

        @Override
        public void update(com.tinyclaw.domain.approval.ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }
    }
}
