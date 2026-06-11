package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Component
@Scope("prototype")
@CommandLine.Command(
    name = "show",
    description = "Show summary of a specific agent run",
    mixinStandardHelpOptions = true
)
public class ShowRunCommand implements Callable<Integer> {

    private final RunRepositoryPort runRepository;
    private final MessageRepositoryPort messageRepository;
    private final ToolExecutionRepositoryPort toolExecutionRepository;
    private final UsageRepositoryPort usageRepository;

    public ShowRunCommand(RunRepositoryPort runRepository,
                          MessageRepositoryPort messageRepository,
                          ToolExecutionRepositoryPort toolExecutionRepository,
                          UsageRepositoryPort usageRepository) {
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.usageRepository = usageRepository;
    }

    @CommandLine.Option(names = {"--run-id"}, required = true, description = "Run ID to query")
    private String runId;

    @CommandLine.Option(names = {"--detail"}, description = "Show detailed messages, tool executions and usage")
    private boolean detail;

    @Override
    public Integer call() {
        if (runRepository == null) {
            System.err.println("Run repository not available");
            return 2;
        }

        Optional<AgentRunSummary> maybeRun = runRepository.findById(runId);
        if (maybeRun.isEmpty()) {
            System.err.println("Run not found: " + runId);
            return 2;
        }

        AgentRunSummary run = maybeRun.get();
        int messageCount = messageRepository != null ? messageRepository.findByRunId(runId).size() : 0;
        int toolExecutionCount = toolExecutionRepository != null ? toolExecutionRepository.findByRunId(runId).size() : 0;

        String statusLabel = switch (run.status()) {
            case COMPLETED -> "success";
            case FAILED -> "failed";
            default -> run.status().name().toLowerCase();
        };

        System.out.println("runId: " + run.id());
        System.out.println("sessionId: " + run.sessionId());
        System.out.println("mode: " + (run.mode() != null ? run.mode() : "unknown"));
        System.out.println("status: " + statusLabel);
        System.out.println("turns: " + run.turnCount());
        System.out.println("messages: " + messageCount);
        System.out.println("toolExecutions: " + toolExecutionCount);

        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        if (usageRepository != null) {
            List<UsageRecord> usageList = usageRepository.findByRunId(runId);
            for (UsageRecord u : usageList) {
                totalPromptTokens += u.promptTokens();
                totalCompletionTokens += u.completionTokens();
            }
        }
        if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
            System.out.println("usage: " + totalPromptTokens + " prompt / " + totalCompletionTokens + " completion tokens");
        } else {
            System.out.println("usage: none");
        }

        System.out.println("error: " + (run.errorReason() != null ? run.errorReason() : ""));

        if (detail) {
            printMessages();
            printToolExecutions();
            printUsage();
        }

        return 0;
    }

    private void printMessages() {
        if (messageRepository == null) {
            return;
        }
        List<AgentMessageDto> messages = messageRepository.findByRunId(runId);
        if (messages.isEmpty()) {
            return;
        }
        System.out.println("\nmessages:");
        int idx = 1;
        for (AgentMessageDto msg : messages) {
            String label = switch (msg.role()) {
                case USER -> "user";
                case ASSISTANT -> msg.toolCallsJson() != null ? "assistant (tool_calls)" : "assistant";
                case SYSTEM -> "system";
            };
            if (msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
                label = "tool_observation (" + msg.toolCallId() + ")";
            }
            System.out.println("  [" + idx + "] " + label + ": " +
                (msg.content() != null ? msg.content() : ""));
            idx++;
        }
    }

    private void printToolExecutions() {
        if (toolExecutionRepository == null) {
            return;
        }
        List<ToolExecutionRecord> tools = toolExecutionRepository.findByRunId(runId);
        if (tools.isEmpty()) {
            return;
        }
        System.out.println("\ntoolExecutions:");
        for (ToolExecutionRecord t : tools) {
            System.out.println("  - " + t.toolName() + " (" + t.stepId() + ")");
            System.out.println("    arguments: " + t.argumentsJson());
            System.out.println("    result: " + t.output());
            System.out.println("    error: " + t.isError());
        }
    }

    private void printUsage() {
        if (usageRepository == null) {
            return;
        }
        List<UsageRecord> usageList = usageRepository.findByRunId(runId);
        if (usageList.isEmpty()) {
            return;
        }
        System.out.println("\nusage:");
        int totalPrompt = 0;
        int totalCompletion = 0;
        int turn = 1;
        for (UsageRecord u : usageList) {
            System.out.println("  - turn " + turn + ": " + u.promptTokens() + " prompt / " + u.completionTokens() + " completion tokens");
            totalPrompt += u.promptTokens();
            totalCompletion += u.completionTokens();
            turn++;
        }
        System.out.println("  total: " + totalPrompt + " prompt / " + totalCompletion + " completion tokens");
    }
}
