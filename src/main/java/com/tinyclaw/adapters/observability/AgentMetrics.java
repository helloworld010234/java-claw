package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.observability.AgentMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 业务自定义 Metrics 注册器。
 *
 * <p>暴露以下 Metrics 到 Micrometer Registry：</p>
 * <ul>
 *   <li>{@code tinyclaw.runs.total} — 总运行次数（Counter, tag: status）</li>
 *   <li>{@code tinyclaw.tools.executed} — 工具执行次数（Counter, tag: tool_name, status）</li>
 *   <li>{@code tinyclaw.llm.latency} — LLM 调用延迟（Timer, tag: phase）</li>
 *   <li>{@code tinyclaw.context.size} — 上下文消息数（Gauge）</li>
 * </ul>
 */
@Component
public class AgentMetrics implements AgentMetricsPort {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger contextSize = new AtomicInteger(0);

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("tinyclaw.context.size", contextSize, AtomicInteger::get)
            .description("Current session context message count")
            .register(meterRegistry);
    }

    /**
     * 记录一次 Run 完成。
     *
     * @param status running / completed / failed
     */
    public void recordRun(String status) {
        Counter.builder("tinyclaw.runs.total")
            .tag("status", status)
            .register(meterRegistry)
            .increment();
    }

    /**
     * 记录一次工具执行。
     *
     * @param toolName 工具名称
     * @param success  是否成功
     */
    public void recordToolExecution(String toolName, boolean success) {
        Counter.builder("tinyclaw.tools.executed")
            .tag("tool_name", toolName)
            .tag("status", success ? "success" : "failure")
            .register(meterRegistry)
            .increment();
    }

    /**
     * 创建 LLM 调用延迟 Timer Sample。
     *
     * @param phase thinking 或 action
     * @return Timer 实例，调用方负责记录时间
     */
    public Timer getLlmLatencyTimer(String phase) {
        return Timer.builder("tinyclaw.llm.latency")
            .tag("phase", phase)
            .register(meterRegistry);
    }

    /**
     * 更新上下文大小仪表盘。
     *
     * @param size 当前消息数
     */
    public void updateContextSize(int size) {
        contextSize.set(size);
    }
}
