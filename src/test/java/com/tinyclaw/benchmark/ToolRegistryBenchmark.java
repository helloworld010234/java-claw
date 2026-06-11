package com.tinyclaw.benchmark;

import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH 基准测试：ToolRegistry.execute() 性能。
 *
 * <p>测量工具查找、策略检查、工具执行的吞吐量。</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ToolRegistryBenchmark {

    private ToolRegistry toolRegistry;
    private ToolCall toolCall;
    private ToolExecutionContext context;

    @Setup
    public void setup() {
        AgentTool echoTool = new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes the input", "{\"type\": \"object\"}");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.success(call.id(), call.argumentsJson());
            }
        };

        List<AgentTool> tools = List.of(echoTool);
        List<ToolExecutionPolicy> policies = List.of(new AllowAllPolicy());
        toolRegistry = new ToolRegistry(tools, policies, null);

        toolCall = ToolCall.of("tc-1", "echo", "{\"message\": \"hello\"}");
        context = new ToolExecutionContext(Paths.get("D:\\test"), "run-1", "sess-1");
    }

    @Benchmark
    public ToolResult executeTool() {
        return toolRegistry.execute(toolCall, context);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(ToolRegistryBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
