package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * CLI 命令：查询指定 run 的审计摘要。
 */
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

    public ShowRunCommand(RunRepositoryPort runRepository,
                          MessageRepositoryPort messageRepository,
                          ToolExecutionRepositoryPort toolExecutionRepository) {
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.toolExecutionRepository = toolExecutionRepository;
    }

    @CommandLine.Option(
        names = {"--run-id"},
        required = true,
        description = "Run ID to query"
    )
    private String runId;

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
        System.out.println("error: " + (run.errorReason() != null ? run.errorReason() : ""));

        return 0;
    }
}
