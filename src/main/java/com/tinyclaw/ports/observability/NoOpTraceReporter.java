package com.tinyclaw.ports.observability;

import java.util.Map;

/**
 * No-op {@link TraceReporter} implementation for environments where tracing is disabled.
 *
 * <p>All methods are empty operations. This class lives in the {@code ports} layer
 * so that {@code application} code can fall back to a safe default without
 * depending on {@code adapters}.</p>
 */
public final class NoOpTraceReporter implements TraceReporter {

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
