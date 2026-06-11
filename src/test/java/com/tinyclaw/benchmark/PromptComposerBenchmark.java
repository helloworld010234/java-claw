package com.tinyclaw.benchmark;

import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.ports.workspace.SkillLoader;
import com.tinyclaw.ports.workspace.WorkspaceGuideLoader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JMH 基准测试：PromptComposer.compose() 性能。
 *
 * <p>测量提示词组合（字符串拼接、文件读取、技能加载）的吞吐量。</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PromptComposerBenchmark {

    private PromptComposer promptComposer;

    @Setup
    public void setup() {
        WorkspaceGuideLoader guideLoader = mock(WorkspaceGuideLoader.class);
        when(guideLoader.loadGuide("D:\\test-workspace")).thenReturn(Optional.of("# Workspace Guide\n\nThis is a test guide."));

        SkillLoader skillLoader = mock(SkillLoader.class);
        when(skillLoader.loadSkills("D:\\test-workspace")).thenReturn(Optional.of("## Skills\n\n- test_skill"));

        promptComposer = new PromptComposer(false, guideLoader, skillLoader);
    }

    @Benchmark
    public Message composePrompt() {
        return promptComposer.compose("D:\\test-workspace");
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(PromptComposerBenchmark.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
