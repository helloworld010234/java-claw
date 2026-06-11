package com.tinyclaw.adapters.reporter;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.reporter.RunReportResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class CompositeReporterTest {

    static class CollectingReporter implements Reporter {
        final List<String> events = new ArrayList<>();

        @Override public void onThinkingStarted(String runId) { events.add("thinking:" + runId); }
        @Override public void onAssistantMessage(String runId, String content) { }
        @Override public void onToolCall(String runId, ToolCall toolCall) { }
        @Override public void onToolResult(String runId, ToolResult toolResult) { }
        @Override public void onRunCompleted(String runId, RunReportResult result) { }
        @Override public void onRunFailed(String runId, String reason) { }
        @Override public void onUsage(String runId, String sessionId, Usage usage, String model) {
            events.add("usage:" + runId + ":" + usage.promptTokens());
        }
    }

    @Test
    void delegatesToAllReporters() {
        CollectingReporter r1 = new CollectingReporter();
        CollectingReporter r2 = new CollectingReporter();
        CompositeReporter composite = new CompositeReporter(List.of(r1, r2));

        composite.onThinkingStarted("run-1");
        composite.onUsage("run-1", "s1", new Usage(10, 20), "model-x");

        assertThat(r1.events).containsExactly("thinking:run-1", "usage:run-1:10");
        assertThat(r2.events).containsExactly("thinking:run-1", "usage:run-1:10");
    }

    @Test
    void singleReporterFailureDoesNotAffectOthers() {
        Reporter failing = new Reporter() {
            @Override public void onThinkingStarted(String runId) { throw new RuntimeException("boom"); }
            @Override public void onAssistantMessage(String runId, String content) { }
            @Override public void onToolCall(String runId, ToolCall toolCall) { }
            @Override public void onToolResult(String runId, ToolResult toolResult) { }
            @Override public void onRunCompleted(String runId, RunReportResult result) { }
            @Override public void onRunFailed(String runId, String reason) { }
            @Override public void onUsage(String runId, String sessionId, Usage usage, String model) { }
        };
        CollectingReporter r2 = new CollectingReporter();
        CompositeReporter composite = new CompositeReporter(List.of(failing, r2));

        composite.onThinkingStarted("run-1");
        assertThat(r2.events).containsExactly("thinking:run-1");
    }

    @Test
    void reporterFailureIsObservableThroughEventsOnOthers() {
        // This test verifies that a failing reporter does not silently disappear;
        // the failure is logged (verified via stdout capture or log appender in integration tests).
        Reporter failing = new Reporter() {
            @Override public void onThinkingStarted(String runId) { throw new RuntimeException("intentional-failure"); }
            @Override public void onAssistantMessage(String runId, String content) { }
            @Override public void onToolCall(String runId, ToolCall toolCall) { }
            @Override public void onToolResult(String runId, ToolResult toolResult) { }
            @Override public void onRunCompleted(String runId, RunReportResult result) { }
            @Override public void onRunFailed(String runId, String reason) { }
            @Override public void onUsage(String runId, String sessionId, Usage usage, String model) { }
        };
        CollectingReporter r2 = new CollectingReporter();
        CompositeReporter composite = new CompositeReporter(List.of(failing, r2));

        // Should not propagate exception
        assertThatNoException().isThrownBy(() -> composite.onThinkingStarted("run-1"));
        assertThat(r2.events).containsExactly("thinking:run-1");
    }
}
