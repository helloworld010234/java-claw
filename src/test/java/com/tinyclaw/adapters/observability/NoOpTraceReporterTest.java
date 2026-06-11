package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.observability.TraceReporter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class NoOpTraceReporterTest {

    private final NoOpTraceReporter reporter = new NoOpTraceReporter();

    @Test
    void startSpanReturnsNonNullHandle() {
        TraceReporter.SpanHandle span = reporter.startSpan("test", Map.of("k", "v"));
        assertThat(span).isNotNull();
    }

    @Test
    void endSpanDoesNotThrow() {
        TraceReporter.SpanHandle span = reporter.startSpan("test", null);
        assertThatNoException().isThrownBy(() -> reporter.endSpan(span));
    }

    @Test
    void recordEventDoesNotThrow() {
        TraceReporter.SpanHandle span = reporter.startSpan("test", null);
        assertThatNoException().isThrownBy(() -> reporter.recordEvent(span, "event", Map.of("k", "v")));
    }

    @Test
    void addAttributeDoesNotThrow() {
        TraceReporter.SpanHandle span = reporter.startSpan("test", null);
        assertThatNoException().isThrownBy(() -> reporter.addAttribute(span, "key", "value"));
    }

    @Test
    void sameHandleReturnedByStartSpan() {
        TraceReporter.SpanHandle span1 = reporter.startSpan("a", null);
        TraceReporter.SpanHandle span2 = reporter.startSpan("b", Map.of());
        // NoOp returns the same singleton instance
        assertThat(span1).isSameAs(span2);
    }
}
