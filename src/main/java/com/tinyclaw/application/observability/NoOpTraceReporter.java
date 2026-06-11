package com.tinyclaw.application.observability;

import com.tinyclaw.ports.observability.TraceReporter;

import java.util.Map;

/**
 * No-op {@link TraceReporter} implementation for the application layer.
 *
 * <p>Used as a safe default when no tracing adapter is configured.
 * All methods are intentionally empty.</p>
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
