package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.observability.AgentMetricsPort;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class AgentRunExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunExecutionService.class);
    private static final int DEFAULT_SESSION_MEMORY_LIMIT = 50;

    private final RunRepositoryPort runRepository;
    private final MessageRepositoryPort messageRepository;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final Reporter reporter;
    private final UsageRepositoryPort usageRepository;
    private final AgentMetricsPort agentMetrics;

    public AgentRunExecutionService(RunRepositoryPort runRepository,
                                     MessageRepositoryPort messageRepository,
                                     SessionService sessionService,
                                     ObjectMapper objectMapper,
                                     Reporter reporter) {
        this(runRepository, messageRepository, sessionService, objectMapper, reporter, null, null);
    }

    public AgentRunExecutionService(RunRepositoryPort runRepository,
                                     MessageRepositoryPort messageRepository,
                                     SessionService sessionService,
                                     ObjectMapper objectMapper,
                                     Reporter reporter,
                                     UsageRepositoryPort usageRepository) {
        this(runRepository, messageRepository, sessionService, objectMapper, reporter, usageRepository, null);
    }

    public AgentRunExecutionService(RunRepositoryPort runRepository,
                                     MessageRepositoryPort messageRepository,
                                     SessionService sessionService,
                                     ObjectMapper objectMapper,
                                     Reporter reporter,
                                     UsageRepositoryPort usageRepository,
                                     AgentMetricsPort agentMetrics) {
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.sessionService = DomainGuards.requireNonNull(sessionService, "sessionService");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
        this.reporter = DomainGuards.requireNonNull(reporter, "reporter");
        this.usageRepository = usageRepository;
        this.agentMetrics = agentMetrics;
    }

    public AgentRunResult execute(String runId,
                                   Session session,
                                   String prompt,
                                   ToolExecutionContext context,
                                   AgentEngine engine,
                                   String engineType,
                                   ToolExecutionRepositoryPort toolExecutionRepository,
                                   int maxTurns) {
        DomainGuards.requireNonNull(runId, "runId");
        DomainGuards.requireNonNull(session, "session");
        DomainGuards.requireNonNull(prompt, "prompt");
        DomainGuards.requireNonNull(context, "context");
        DomainGuards.requireNonNull(engine, "engine");
        DomainGuards.requirePositive(maxTurns, "maxTurns");

        if (runRepository != null) {
            runRepository.saveSession(session);
        }

        AgentRun run = AgentRun.start(runId, session.id(), maxTurns, Instant.now());
        if (runRepository != null) {
            runRepository.saveRunStarted(run, engineType, prompt);
        }

        List<Message> messagesBefore = List.copyOf(sessionService.getWorkingMemory(session.id()));
        if (messageRepository != null) {
            List<AgentMessageDto> historyDtos = messageRepository.findBySessionId(session.id(), DEFAULT_SESSION_MEMORY_LIMIT);
            List<Message> history = historyDtos.stream()
                .map(dto -> dto.toMessage(objectMapper))
                .toList();
            sessionService.replaceMessages(session.id(), history);
            messagesBefore = List.copyOf(sessionService.getWorkingMemory(session.id()));
        }

        AgentRunResult result;
        boolean engineThrew = false;
        if (agentMetrics != null) {
            agentMetrics.recordRun("running");
        }
        try {
            result = engine.run(run, session, prompt, context, toolExecutionRepository);
        } catch (Exception e) {
            engineThrew = true;
            if (runRepository != null) {
                runRepository.saveRunFailed(runId, run.currentTurn(), e.getMessage(), Instant.now());
            }
            if (agentMetrics != null) {
                agentMetrics.recordRun("failed");
            }
            throw e;
        } finally {
            persistDeltaMessages(runId, session, messagesBefore);
            if (engineThrew) {
                // Usage not persisted when engine throws unexpectedly;
                // any messages produced before the throw have been saved above.
            }
        }

        if (runRepository != null) {
            if (result.waitingForApproval()) {
                String approvalId = extractApprovalId(result);
                runRepository.saveRunWaitingForApproval(runId, result.turnCount(), approvalId, Instant.now());
            } else if (result.success()) {
                runRepository.saveRunCompleted(runId, result.turnCount(), Instant.now());
            } else {
                runRepository.saveRunFailed(runId, result.turnCount(), result.errorReason(), Instant.now());
            }
        }

        if (agentMetrics != null) {
            agentMetrics.recordRun(result.success() ? "completed" : "failed");
        }

        if (usageRepository != null && result != null && result.totalUsage() != null) {
            persistUsageAggregate(runId, session.id(), result.totalUsage(), engineType);
        }

        return result;
    }

    private String extractApprovalId(AgentRunResult result) {
        String reason = result.errorReason();
        if (reason == null || !reason.startsWith("Approval required: ")) {
            return "";
        }
        String remainder = reason.substring("Approval required: ".length());
        int spaceIdx = remainder.indexOf(' ');
        return spaceIdx > 0 ? remainder.substring(0, spaceIdx) : remainder;
    }

    private void persistDeltaMessages(String runId, Session session, List<Message> messagesBefore) {
        if (messageRepository == null) {
            return;
        }
        List<Message> messagesAfter = sessionService.getWorkingMemory(session.id());
        int beforeCount = messagesBefore.size();
        List<Message> newMessages = messagesAfter.stream()
            .skip(beforeCount)
            .filter(m -> m.role() != Role.SYSTEM)
            .toList();
        for (Message m : newMessages) {
            messageRepository.append(runId, session.id(), m);
        }
    }

    private void persistUsageAggregate(String runId, String sessionId, Usage usage, String engineType) {
        UsageRecord record = new UsageRecord(
            runId,
            sessionId,
            engineType != null && !engineType.isBlank() ? engineType : "unknown",
            usage.promptTokens(),
            usage.completionTokens(),
            null,
            true,
            Instant.now()
        );
        try {
            usageRepository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist usage aggregate for run {}: {}", runId, e.getMessage());
        }
    }
}
