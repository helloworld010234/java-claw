package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.observability.TraceReporter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;

import java.util.Map;

/**
 * 基于 Micrometer Tracing 的 {@link TraceReporter} 实现。
 *
 * <p>适配 Micrometer 的 {@link Tracer} 和 {@link Span} API，
 * 支持 span 创建、属性设置、事件记录和嵌套追踪。</p>
 */
public class MicrometerTraceReporter implements TraceReporter {

    private final Tracer tracer;

    public MicrometerTraceReporter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public SpanHandle startSpan(String name, Map<String, String> attributes) {
        Span span = tracer.nextSpan().name(name).start();
        if (attributes != null) {
            attributes.forEach(span::tag);
        }
        return new MicrometerSpanHandle(span);
    }

    @Override
    public void endSpan(SpanHandle span) {
        if (span instanceof MicrometerSpanHandle msh) {
            msh.span.end();
        }
    }

    @Override
    public void recordEvent(SpanHandle span, String eventName, Map<String, String> attributes) {
        if (span instanceof MicrometerSpanHandle msh) {
            msh.span.event(eventName);
            if (attributes != null) {
                attributes.forEach(msh.span::tag);
            }
        }
    }

    @Override
    public void addAttribute(SpanHandle span, String key, String value) {
        if (span instanceof MicrometerSpanHandle msh) {
            msh.span.tag(key, value);
        }
    }

    private static final class MicrometerSpanHandle implements SpanHandle {
        final Span span;

        MicrometerSpanHandle(Span span) {
            this.span = span;
        }
    }
}
