package com.tinyclaw.adapters.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentMetrics} 单元测试。
 *
 * <p>验证自定义 Metrics 正确注册到 Micrometer Registry 并产生预期数据。</p>
 */
class AgentMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private AgentMetrics agentMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        agentMetrics = new AgentMetrics(meterRegistry);
    }

    @Test
    void recordRun_shouldIncrementCounterWithStatusTag() {
        agentMetrics.recordRun("completed");
        agentMetrics.recordRun("completed");
        agentMetrics.recordRun("failed");

        // Each status gets its own counter; verify total across all statuses
        double total = meterRegistry.find("tinyclaw.runs.total")
            .counters().stream()
            .mapToDouble(Counter::count)
            .sum();
        assertThat(total).isEqualTo(3.0);
    }

    @Test
    void recordRun_shouldCreateSeparateCountersForDifferentStatuses() {
        agentMetrics.recordRun("running");
        agentMetrics.recordRun("completed");
        agentMetrics.recordRun("failed");

        assertThat(meterRegistry.find("tinyclaw.runs.total")
            .tag("status", "running").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tinyclaw.runs.total")
            .tag("status", "completed").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tinyclaw.runs.total")
            .tag("status", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordToolExecution_shouldIncrementCounterWithToolNameAndStatusTags() {
        agentMetrics.recordToolExecution("read_file", true);
        agentMetrics.recordToolExecution("read_file", true);
        agentMetrics.recordToolExecution("write_file", false);

        assertThat(meterRegistry.find("tinyclaw.tools.executed")
            .tag("tool_name", "read_file").tag("status", "success").counter().count())
            .isEqualTo(2.0);
        assertThat(meterRegistry.find("tinyclaw.tools.executed")
            .tag("tool_name", "write_file").tag("status", "failure").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void getLlmLatencyTimer_shouldReturnTimerWithPhaseTag() {
        Timer thinkingTimer = agentMetrics.getLlmLatencyTimer("thinking");
        Timer actionTimer = agentMetrics.getLlmLatencyTimer("action");

        thinkingTimer.record(() -> {
            // simulate work
        });
        actionTimer.record(() -> {
            // simulate work
        });

        assertThat(meterRegistry.find("tinyclaw.llm.latency")
            .tag("phase", "thinking").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("tinyclaw.llm.latency")
            .tag("phase", "action").timer().count()).isEqualTo(1);
    }

    @Test
    void updateContextSize_shouldUpdateGaugeValue() {
        agentMetrics.updateContextSize(42);

        Gauge gauge = meterRegistry.find("tinyclaw.context.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);

        agentMetrics.updateContextSize(100);
        assertThat(gauge.value()).isEqualTo(100.0);
    }

    @Test
    void constructor_shouldRegisterContextSizeGauge() {
        // Gauge 在构造函数中注册
        Gauge gauge = meterRegistry.find("tinyclaw.context.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }
}
