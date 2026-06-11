package com.tinyclaw.application.chatops;

import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> seenEventIds;

    public ChatOpsEventHandler(AgentRunExecutionService executionService,
                               SessionService sessionService,
                               ChatOpsMessageSender messageSender,
                               ExecutorService executor,
                               Path chatOpsWorkspace,
                               int maxTurns) {
        this.executionService = executionService;
        this.sessionService = sessionService;
        this.messageSender = messageSender;
        this.executor = executor;
        this.chatOpsWorkspace = chatOpsWorkspace;
        this.maxTurns = maxTurns;
        this.seenEventIds = ConcurrentHashMap.newKeySet();
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

        if (!seenEventIds.add(event.eventId())) {
            log.info("[ChatOps] Duplicate event ignored: {}", event.eventId());
            return false;
        }

        if (event.isUrlVerification()) {
            // URL verification is handled at the adapter layer (challenge response)
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
                    null, "chatops", null, maxTurns
                );
                if (result.success()) {
                    messageSender.sendMessage(event.chatId(),
                        ChatOpsOutboundMessage.runCompleted(runId, result.turnCount(), result.finalMessage()));
                } else {
                    messageSender.sendMessage(event.chatId(),
                        ChatOpsOutboundMessage.runFailed(runId, result.errorReason() != null ? result.errorReason() : "unknown"));
                }
            } catch (Exception e) {
                log.error("[Run {}] ChatOps run failed: {}", runId, e.getMessage(), e);
                messageSender.sendMessage(event.chatId(),
                    ChatOpsOutboundMessage.runFailed(runId, e.getMessage()));
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
        return seenEventIds.contains(eventId);
    }
}
