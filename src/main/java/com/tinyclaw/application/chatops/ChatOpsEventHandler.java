package com.tinyclaw.application.chatops;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.ChatOpsSanitizer;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Application service that converts chat events into Agent Run requests.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Deduplicate events by {@code eventId}</li>
 *   <li>Map chatId → stable sessionId</li>
 *   <li>Resolve workDir from controlled config (never from user message)</li>
 *   <li>Trigger async Agent Run via {@link AgentRunExecutionService}</li>
 *   <li>Report run lifecycle to the chat channel via {@link ChatOpsMessageSender}</li>
 * </ul>
 */
public class ChatOpsEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatOpsEventHandler.class);

    private final AgentRunExecutionService executionService;
    private final SessionService sessionService;
    private final ChatOpsMessageSender messageSender;
    private final ExecutorService executor;
    private final Path chatOpsWorkspace;
    private final int maxTurns;
    private final Map<String, Boolean> seenEventIds;
    private final AgentEngine agentEngine;
    private final ChatOpsApprovalCommandHandler approvalCommandHandler;
    private static final int MAX_SEEN_EVENTS = 10_000;

    public ChatOpsEventHandler(AgentRunExecutionService executionService,
                               SessionService sessionService,
                               ChatOpsMessageSender messageSender,
                               ExecutorService executor,
                               Path chatOpsWorkspace,
                               int maxTurns,
                               AgentEngine agentEngine) {
        this(executionService, sessionService, messageSender, executor, chatOpsWorkspace,
            maxTurns, agentEngine, null);
    }

    public ChatOpsEventHandler(AgentRunExecutionService executionService,
                               SessionService sessionService,
                               ChatOpsMessageSender messageSender,
                               ExecutorService executor,
                               Path chatOpsWorkspace,
                               int maxTurns,
                               AgentEngine agentEngine,
                               ChatOpsApprovalCommandHandler approvalCommandHandler) {
        this.executionService = executionService;
        this.sessionService = sessionService;
        this.messageSender = messageSender;
        this.executor = executor;
        this.chatOpsWorkspace = chatOpsWorkspace;
        this.maxTurns = maxTurns;
        this.agentEngine = agentEngine;
        this.approvalCommandHandler = approvalCommandHandler;
        this.seenEventIds = Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_SEEN_EVENTS;
            }
        });
    }

    /**
     * Handle an incoming chat event.
     *
     * @param event the normalized chat event
     * @return true if the event was accepted for processing
     */
    public boolean handle(ChatOpsEvent event) {
        if (event == null) {
            log.warn("[ChatOps] Received null event");
            return false;
        }

        if (seenEventIds.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
            log.info("[ChatOps] Duplicate event ignored: {}", event.eventId());
            return false;
        }

        if (event.isUrlVerification()) {
            // URL verification is handled at the adapter layer (challenge response)
            return true;
        }

        if (approvalCommandHandler != null && approvalCommandHandler.handle(event, messageSender)) {
            return true;
        }

        if (!event.isRunnableTextMessage()) {
            log.info("[ChatOps] Event not runnable: type={}, text blank={}",
                event.type(), event.text() == null || event.text().isBlank());
            return false;
        }

        String runId = UUID.randomUUID().toString();
        String sessionId = deriveSessionId(event.chatId());
        String prompt = event.text().trim();

        Session session = Session.create(sessionId, chatOpsWorkspace.toString(), Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(chatOpsWorkspace, runId, sessionId);

        messageSender.sendMessage(event.chatId(), ChatOpsOutboundMessage.runStarted(runId, prompt));

        executor.submit(() -> {
            try {
                log.info("[Run {}] ChatOps run started from chat {}", runId, event.chatId());
                var result = executionService.execute(
                    runId, session, prompt, context,
                    agentEngine, "chatops", null, maxTurns
                );
                if (result.success()) {
                    messageSender.sendMessage(event.chatId(),
                        ChatOpsOutboundMessage.runCompleted(runId, result.turnCount(), result.finalMessage()));
                } else {
                    messageSender.sendMessage(event.chatId(),
                        ChatOpsOutboundMessage.runFailed(runId, result.errorReason() != null ? result.errorReason() : "unknown"));
                }
            } catch (Exception e) {
                String safeReason = ChatOpsSanitizer.sanitize(e.getMessage());
                log.error("[Run {}] ChatOps run failed: type={}, message={}",
                    runId, e.getClass().getSimpleName(), safeReason);
                messageSender.sendMessage(event.chatId(),
                    ChatOpsOutboundMessage.runFailed(runId, safeReason));
            }
        });

        return true;
    }

    /**
     * Derive a stable sessionId from the chat channel identifier.
     */
    static String deriveSessionId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return "chatops-default";
        }
        return "chatops-" + chatId;
    }

    /**
     * Exposed for testing: check whether an eventId has been seen.
     */
    boolean isDuplicate(String eventId) {
        return seenEventIds.containsKey(eventId);
    }
}
