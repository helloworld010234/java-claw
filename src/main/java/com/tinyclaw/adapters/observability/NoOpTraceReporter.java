package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.observability.TraceReporter;

import java.util.Map;

/**
 * 无操作（NoOp）的 {@link TraceReporter} 实现。
 *
 * <p>所有方法为空操作，用于测试环境或 trace 功能关闭时。
 * 向后兼容：不引入追踪依赖的默认实现。</p>
 */
public class NoOpTraceReporter implements TraceReporter {

    private static final SpanHandle INSTANCE = new NoOpSpanHandle();

    @Override
    public SpanHandle startSpan(String name, Map<String, String> attributes) {
        return INSTANCE;
    }

    @Override
    public void endSpan(SpanHandle span) {
        // no-op
    }

    @Override
    public void recordEvent(SpanHandle span, String eventName, Map<String, String> attributes) {
        // no-op
    }

    @Override
    public void addAttribute(SpanHandle span, String key, String value) {
        // no-op
    }

    private static final class NoOpSpanHandle implements SpanHandle {
    }
}
