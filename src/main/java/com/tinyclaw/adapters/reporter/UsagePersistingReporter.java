package com.tinyclaw.adapters.reporter;

import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.reporter.RunReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * @deprecated Usage persistence is now handled by {@link com.tinyclaw.application.run.AgentRunExecutionService}
 * as a critical lifecycle action. This reporter is no longer in the critical path.
 */
@Deprecated
public class UsagePersistingReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(UsagePersistingReporter.class);

    private final UsageRepositoryPort usageRepository;
    private final TinyClawModelProperties modelProperties;

    public UsagePersistingReporter(UsageRepositoryPort usageRepository,
                                    TinyClawModelProperties modelProperties) {
        this.usageRepository = usageRepository;
        this.modelProperties = modelProperties != null ? modelProperties : new TinyClawModelProperties();
    }

    @Override
    public void onUsage(String runId, String sessionId, Usage usage, String model) {
        double cost = estimateCost(usage);
        UsageRecord record = new UsageRecord(
            runId,
            sessionId,
            model != null && !model.isBlank() ? model : "unknown",
            usage.promptTokens(),
            usage.completionTokens(),
            cost > 0 ? cost : null,
            true,
            Instant.now()
        );
        try {
            usageRepository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist usage for run {}: {}", runId, e.getMessage());
        }
    }

    private double estimateCost(Usage usage) {
        if (modelProperties == null || modelProperties.getPricing() == null) {
            return 0.0;
        }
        TinyClawModelProperties.Pricing pricing = modelProperties.getPricing();
        return (usage.promptTokens() * pricing.getInputPricePer1M()
                + usage.completionTokens() * pricing.getOutputPricePer1M()) / 1_000_000.0;
    }

    @Override public void onThinkingStarted(String runId) { }
    @Override public void onAssistantMessage(String runId, String content) { }
    @Override public void onToolCall(String runId, ToolCall toolCall) { }
    @Override public void onToolResult(String runId, ToolResult toolResult) { }
    @Override public void onRunCompleted(String runId, RunReportResult result) { }
    @Override public void onRunFailed(String runId, String reason) { }
    @Override public void onRunWaitingForApproval(String runId, String approvalId, String toolName, String argsPreview) { }
}
