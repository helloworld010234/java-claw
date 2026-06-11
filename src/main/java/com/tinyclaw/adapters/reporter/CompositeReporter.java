package com.tinyclaw.adapters.reporter;

import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.reporter.Reporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CompositeReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(CompositeReporter.class);
    private final List<Reporter> reporters;

    public CompositeReporter(List<Reporter> reporters) {
        this.reporters = List.copyOf(reporters);
    }

    @Override
    public void onThinkingStarted(String runId) {
        for (Reporter r : reporters) {
            try {
                r.onThinkingStarted(runId);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onThinkingStarted: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onAssistantMessage(String runId, String content) {
        for (Reporter r : reporters) {
            try {
                r.onAssistantMessage(runId, content);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onAssistantMessage: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onToolCall(String runId, ToolCall toolCall) {
        for (Reporter r : reporters) {
            try {
                r.onToolCall(runId, toolCall);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onToolCall: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onToolResult(String runId, ToolResult toolResult) {
        for (Reporter r : reporters) {
            try {
                r.onToolResult(runId, toolResult);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onToolResult: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onRunCompleted(String runId, AgentRunResult result) {
        for (Reporter r : reporters) {
            try {
                r.onRunCompleted(runId, result);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onRunCompleted: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onRunFailed(String runId, String reason) {
        for (Reporter r : reporters) {
            try {
                r.onRunFailed(runId, reason);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onRunFailed: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void onUsage(String runId, String sessionId, Usage usage, String model) {
        for (Reporter r : reporters) {
            try {
                r.onUsage(runId, sessionId, usage, model);
            } catch (Exception e) {
                log.warn("Reporter {} failed on onUsage: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
