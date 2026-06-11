package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.observability.TraceReporter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MicrometerTraceReporterTest {

    private final Tracer tracer = mock(Tracer.class);
    private final Span span = mock(Span.class);
    private final MicrometerTraceReporter reporter = new MicrometerTraceReporter(tracer);

    @Test
    void startSpanCreatesAndStartsSpan() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(any())).thenReturn(span);
        when(span.start()).thenReturn(span);

        TraceReporter.SpanHandle handle = reporter.startSpan("test-span", Map.of("k", "v"));

        assertThat(handle).isNotNull();
        verify(span).name("test-span");
        verify(span).start();
        verify(span).tag("k", "v");
    }

    @Test
    void startSpanWithNullAttributesDoesNotTag() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(any())).thenReturn(span);
        when(span.start()).thenReturn(span);

        reporter.startSpan("test-span", null);

        verify(span, never()).tag(any(), any());
    }

    @Test
    void endSpanEndsUnderlyingSpan() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(any())).thenReturn(span);
        when(span.start()).thenReturn(span);

        TraceReporter.SpanHandle handle = reporter.startSpan("test", null);
        reporter.endSpan(handle);

        verify(span).end();
    }

    @Test
    void recordEventAddsEventAndTags() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(any())).thenReturn(span);
        when(span.start()).thenReturn(span);

        TraceReporter.SpanHandle handle = reporter.startSpan("test", null);
        reporter.recordEvent(handle, "my-event", Map.of("ek", "ev"));

        verify(span).event("my-event");
        verify(span).tag("ek", "ev");
    }

    @Test
    void addAttributeTagsSpan() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(any())).thenReturn(span);
        when(span.start()).thenReturn(span);

        TraceReporter.SpanHandle handle = reporter.startSpan("test", null);
        reporter.addAttribute(handle, "key", "value");

        verify(span).tag("key", "value");
    }

    @Test
    void operationsOnUnknownHandleAreNoOp() {
        TraceReporter.SpanHandle unknown = mock(TraceReporter.SpanHandle.class);

        assertThatNoException().isThrownBy(() -> {
            reporter.endSpan(unknown);
            reporter.recordEvent(unknown, "e", Map.of());
            reporter.addAttribute(unknown, "k", "v");
        });

        verifyNoInteractions(span);
    }
}
