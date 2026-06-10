package com.tinyclaw.application.tool;

import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext(Path.of("."));

    @Test
    void findReturnsToolByName() {
        AgentTool tool = tool("read_file", ToolResult.success("call-1", "ok"));
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThat(registry.find("read_file")).containsSame(tool);
    }

    @Test
    void duplicateToolNameThrows() {
        AgentTool first = tool("read_file", ToolResult.success("call-1", "first"));
        AgentTool second = tool("read_file", ToolResult.success("call-1", "second"));

        assertThatThrownBy(() -> new ToolRegistry(List.of(first, second)))
            .isInstanceOf(TinyClawDomainException.class)
            .hasMessageContaining("Duplicate tool name");
    }

    @Test
    void unknownToolReturnsFailure() {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolCall call = ToolCall.of("call-1", "missing", "{}");

        ToolResult result = registry.execute(call, CONTEXT);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Unknown tool");
    }

    @Test
    void toolExecutionExceptionConvertedToFailure() {
        AgentTool throwingTool = new AgentTool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("boom", "Throwing tool", "{}");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                throw new IllegalStateException("broken");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(throwingTool));

        ToolResult result = registry.execute(ToolCall.of("call-1", "boom", "{}"), CONTEXT);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Tool execution failed", "broken");
    }

    @Test
    void policyAllowsExecution() {
        AgentTool tool = tool("read_file", ToolResult.success("call-1", "ok"));
        ToolExecutionPolicy allowPolicy = (call, ctx) -> ToolExecutionDecision.allow();
        ToolRegistry registry = new ToolRegistry(List.of(tool), List.of(allowPolicy));

        ToolResult result = registry.execute(ToolCall.of("call-1", "read_file", "{}"), CONTEXT);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).isEqualTo("ok");
    }

    @Test
    void policyRejectsExecutionWithoutCallingTool() {
        SpyTool spyTool = spyTool("read_file", ToolResult.success("call-1", "should-not-run"));
        ToolExecutionPolicy denyPolicy = (call, ctx) -> ToolExecutionDecision.deny("Blocked by test policy");
        ToolRegistry registry = new ToolRegistry(List.of(spyTool), List.of(denyPolicy));

        ToolResult result = registry.execute(ToolCall.of("call-1", "read_file", "{}"), CONTEXT);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).isEqualTo("Blocked by test policy");
        assertThat(spyTool.executeWasCalled).isFalse();
    }

    @Test
    void multiplePoliciesEvaluatedInOrder() {
        AgentTool tool = tool("read_file", ToolResult.success("call-1", "ok"));
        ToolExecutionPolicy allowPolicy = (call, ctx) -> ToolExecutionDecision.allow();
        ToolExecutionPolicy denyPolicy = (call, ctx) -> ToolExecutionDecision.deny("Second policy blocks");
        ToolRegistry registry = new ToolRegistry(List.of(tool), List.of(allowPolicy, denyPolicy));

        ToolResult result = registry.execute(ToolCall.of("call-1", "read_file", "{}"), CONTEXT);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).isEqualTo("Second policy blocks");
    }

    @Test
    void firstRejectionWinsAndSkipsRemainingPolicies() {
        AgentTool tool = tool("read_file", ToolResult.success("call-1", "ok"));
        ToolExecutionPolicy denyPolicy = (call, ctx) -> ToolExecutionDecision.deny("First blocks");
        ToolExecutionPolicy secondPolicy = (call, ctx) -> {
            throw new AssertionError("Should not be called");
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool), List.of(denyPolicy, secondPolicy));

        ToolResult result = registry.execute(ToolCall.of("call-1", "read_file", "{}"), CONTEXT);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).isEqualTo("First blocks");
    }

    private AgentTool tool(String name, ToolResult result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Test tool", "{}");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return result;
            }
        };
    }

    private SpyTool spyTool(String name, ToolResult result) {
        return new SpyTool(name, result);
    }

    private static class SpyTool implements AgentTool {
        private final String name;
        private final ToolResult result;
        boolean executeWasCalled = false;

        SpyTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "Spy tool", "{}");
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            executeWasCalled = true;
            return result;
        }
    }
}
