package com.tinyclaw.benchmark;

import com.tinyclaw.application.engine.AgentContextBuilder;
import com.tinyclaw.application.engine.ContextCompactor;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.engine.WorkingMemorySelector;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.ports.workspace.SkillLoader;
import com.tinyclaw.ports.workspace.WorkspaceGuideLoader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JMH 基准测试：AgentContextBuilder.build() 性能。
 *
 * <p>测量上下文构建（提示词组合 + 工作内存选择 + 压缩）的吞吐量。</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AgentContextBuilderBenchmark {

    private AgentContextBuilder agentContextBuilder;
    private List<Message> sessionMessages;

    @Setup
    public void setup() {
        WorkspaceGuideLoader guideLoader = mock(WorkspaceGuideLoader.class);
        when(guideLoader.loadGuide("D:\\test-workspace")).thenReturn(Optional.of("# Workspace Guide\n\nThis is a test guide."));

        SkillLoader skillLoader = mock(SkillLoader.class);
        when(skillLoader.loadSkills("D:\\test-workspace")).thenReturn(Optional.of("## Skills\n\n- test_skill"));

        PromptComposer promptComposer = new PromptComposer(false, guideLoader, skillLoader);
        WorkingMemorySelector workingMemorySelector = new WorkingMemorySelector();
        ContextCompactor contextCompactor = new ContextCompactor();

        agentContextBuilder = new AgentContextBuilder(promptComposer, workingMemorySelector, contextCompactor);

        sessionMessages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sessionMessages.add(Message.user("User message " + i));
            sessionMessages.add(Message.assistant("Assistant response " + i));
        }
    }

    @Benchmark
    public List<Message> buildContext() {
        return agentContextBuilder.build("D:\\test-workspace", sessionMessages, 10);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(AgentContextBuilderBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
