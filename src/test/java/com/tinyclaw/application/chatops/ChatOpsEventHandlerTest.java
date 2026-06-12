package com.tinyclaw.application.chatops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatOps event handler tests using a real (fake-LLM) engine and direct executor.
 *
 * <p>These tests prove the ChatOps closed loop: event → handler → engine run →
 * sender messages. No mocks are used for the execution path.</p>
 */
class ChatOpsEventHandlerTest {

    private FakeChatOpsMessageSender sender;
    private ChatOpsEventHandler handler;
    private AgentRunExecutionService executionService;
    private SessionService sessionService;
    private ExecutorService directExecutor;

    @BeforeEach
    void setUp() {
        sender = new FakeChatOpsMessageSender();
        sessionService = new InMemorySessionService();
        directExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chatops-test-direct");
            t.setDaemon(true);
            return t;
        });

        // Build a real AgentEngine with a fake LLM that completes in one turn
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("Done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(
            fakeLlm,
            new ToolRegistry(List.of()),
            new PromptComposer(),
            new NoOpReporter(),
            sessionService
        );

        executionService = new AgentRunExecutionService(
            null, null, sessionService, new ObjectMapper(), new NoOpReporter()
        );

        handler = new ChatOpsEventHandler(
            executionService,
            sessionService,
            sender,
            directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(),
            10,
            engine
        );
    }

    @Test
    void nullEventReturnsFalse() {
        assertThat(handler.handle(null)).isFalse();
    }

    @Test
    void urlVerificationReturnsTrue() {
        ChatOpsEvent event = new ChatOpsEvent("evt-1", null, null, null, null, Instant.now(), ChatOpsEvent.Type.URL_VERIFICATION);
        assertThat(handler.handle(event)).isTrue();
        assertThat(sender.getMessages()).isEmpty();
    }

    @Test
    void blankTextMessageReturnsFalse() {
        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "   ", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        assertThat(handler.handle(event)).isFalse();
    }

    @Test
    void unknownEventReturnsFalse() {
        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "hello", Instant.now(), ChatOpsEvent.Type.UNKNOWN);
        assertThat(handler.handle(event)).isFalse();
    }

    @Test
    void validTextMessageTriggersRun() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
            executionService, sessionService, countingSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            new AgentEngine(
                new FakeLlmGateway(List.of(new LlmResponse("Done", List.of(), null))),
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            )
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "hello world", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        assertThat(testHandler.handle(event)).isTrue();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(countingSender.hasMessageContaining("Run started")).isTrue();
        assertThat(countingSender.hasMessageContaining("Run completed")).isTrue();
    }

    @Test
    void duplicateEventIsIgnored() {
        ChatOpsEvent event = new ChatOpsEvent("evt-dup", "msg-1", "chat-1", "user-1", "hello", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        assertThat(handler.handle(event)).isTrue();
        assertThat(handler.handle(event)).isFalse();
        assertThat(handler.isDuplicate("evt-dup")).isTrue();
    }

    @Test
    void concurrentDuplicateEventAcceptedOnlyOnce() throws InterruptedException {
        int threadCount = 10;
        String eventId = "evt-concurrent-dup";
        ChatOpsEvent event = new ChatOpsEvent(eventId, "msg-1", "chat-1", "user-1", "hello", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acceptedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean accepted = handler.handle(event);
                    if (accepted) {
                        acceptedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(acceptedCount.get()).isEqualTo(1);
        assertThat(handler.isDuplicate(eventId)).isTrue();
    }

    @Test
    void sessionIdIsDerivedFromChatId() {
        assertThat(ChatOpsEventHandler.deriveSessionId("oc_abc123")).isEqualTo("chatops-oc_abc123");
        assertThat(ChatOpsEventHandler.deriveSessionId(null)).isEqualTo("chatops-default");
        assertThat(ChatOpsEventHandler.deriveSessionId("")).isEqualTo("chatops-default");
    }

    @Test
    void runIdIsPresentInContext() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
            executionService, sessionService, countingSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            new AgentEngine(
                new FakeLlmGateway(List.of(new LlmResponse("Done", List.of(), null))),
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            )
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-2", "msg-2", "chat-2", "user-2", "run test", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        testHandler.handle(event);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify session was created with correct id
        assertThat(sessionService.getWorkingMemory("chatops-chat-2")).isNotEmpty();
    }

    @Test
    void failedRunSendsFailureMessage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        // Engine that throws on first LLM call
        AgentEngine failingEngine = new AgentEngine(
            new FakeLlmGateway(req -> { throw new com.tinyclaw.ports.llm.LlmException("engine error"); }),
            new ToolRegistry(List.of()),
            new PromptComposer(),
            new NoOpReporter(),
            sessionService
        );

        ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
            executionService, sessionService, countingSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            failingEngine
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-3", "msg-3", "chat-3", "user-3", "fail me", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        testHandler.handle(event);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(countingSender.hasMessageContaining("Run failed")).isTrue();
        assertThat(countingSender.hasMessageContaining("engine error")).isTrue();
    }

    @Test
    void promptIsNormalizedTrimmed() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        // Record the prompt that reaches the engine
        java.util.concurrent.atomic.AtomicReference<String> capturedPrompt = new java.util.concurrent.atomic.AtomicReference<>();
        FakeLlmGateway recordingLlm = new FakeLlmGateway(req -> {
            if (!req.messages().isEmpty()) {
                capturedPrompt.set(req.messages().get(req.messages().size() - 1).content());
            }
            return new LlmResponse("Done", List.of(), null);
        });

        ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
            executionService, sessionService, countingSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            new AgentEngine(
                recordingLlm,
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            )
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-5", "msg-5", "chat-5", "user-5", "  hello world  ", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        testHandler.handle(event);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // The prompt sent to the engine should be trimmed
        assertThat(capturedPrompt.get()).isEqualTo("hello world");
    }

    @Test
    void runStartedMasksSecretInPrompt() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
            executionService, sessionService, countingSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            new AgentEngine(
                new FakeLlmGateway(List.of(new LlmResponse("Done", List.of(), null))),
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            )
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-6", "msg-6", "chat-6", "user-6", "my api_key is sk-live-12345", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        testHandler.handle(event);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(countingSender.hasMessageContaining("Run started")).isTrue();
        // The outbound message must not contain the raw secret
        assertThat(countingSender.getMessages()).allMatch(m -> !m.text().contains("sk-live-12345"));
        assertThat(countingSender.getMessages()).anyMatch(m -> m.text().contains("***"));
    }

    @Test
    void approvalCommandIsHandledByEventHandler() {
        FakeChatOpsMessageSender commandSender = new FakeChatOpsMessageSender();
        InMemoryApprovalRepository approvalRepository = new InMemoryApprovalRepository();
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-chatops", "run-1", "sess-1", "tc-1", "shell_command", "args", Instant.now()
        );
        approvalRepository.save(pending);

        ChatOpsApprovalCommandHandler approvalHandler = new ChatOpsApprovalCommandHandler(
            approvalRepository, Clock.systemUTC()
        );
        ChatOpsEventHandler commandEnabledHandler = new ChatOpsEventHandler(
            executionService, sessionService, commandSender, directExecutor,
            Path.of("/tmp/chatops").toAbsolutePath(), 10,
            new AgentEngine(
                new FakeLlmGateway(List.of(new LlmResponse("Done", List.of(), null))),
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            ),
            approvalHandler
        );

        ChatOpsEvent event = new ChatOpsEvent("evt-approve", "msg-approve", "chat-1", "user-1",
            "approve apr-chatops", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        boolean accepted = commandEnabledHandler.handle(event);

        assertThat(accepted).isTrue();
        ApprovalRequest updated = approvalRepository.findById("apr-chatops").orElseThrow();
        assertThat(updated.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(commandSender.getMessages()).anyMatch(m -> m.text().contains("approved"));
    }

    private static class InMemoryApprovalRepository implements com.tinyclaw.ports.persistence.ApprovalRepositoryPort {
        private final List<ApprovalRequest> requests = new ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public Optional<ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public List<ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public List<ApprovalRequest> findAll() {
            return List.copyOf(requests);
        }

        @Override
        public void update(ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }

        @Override
        public boolean claimForResume(String approvalId, Instant now) {
            return false;
        }
    }

    @Test
    void failedRunWithSecretDoesNotLeakInLogsOrMessages() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FakeChatOpsMessageSender countingSender = new FakeChatOpsMessageSender() {
            @Override
            public void sendMessage(String chatId, com.tinyclaw.ports.chatops.ChatOpsOutboundMessage message) {
                super.sendMessage(chatId, message);
                if (message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_COMPLETED
                    || message.type() == com.tinyclaw.ports.chatops.ChatOpsOutboundMessage.Type.RUN_FAILED) {
                    latch.countDown();
                }
            }
        };

        // Capture logs from ChatOpsEventHandler
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(ChatOpsEventHandler.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);

        try {
            // Engine that throws an exception with a secret in the message
            AgentEngine failingEngine = new AgentEngine(
                new FakeLlmGateway(req -> {
                    throw new com.tinyclaw.ports.llm.LlmException("api_key=sk-secret-123 failed");
                }),
                new ToolRegistry(List.of()),
                new PromptComposer(),
                new NoOpReporter(),
                sessionService
            );

            // Wrap executionService to force an unexpected throw (bypassing AgentEngine's internal catch)
            AgentRunExecutionService throwingExecutionService = new AgentRunExecutionService(
                null, null, sessionService, new ObjectMapper(), new NoOpReporter()
            ) {
                @Override
                public AgentRunResult execute(String runId,
                                               Session session,
                                               String prompt,
                                               ToolExecutionContext context,
                                               AgentEngine engine,
                                               String engineType,
                                               com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort toolExecutionRepository,
                                               int maxTurns) {
                    throw new RuntimeException("api_key=sk-secret-123 crashed");
                }
            };

            ChatOpsEventHandler testHandler = new ChatOpsEventHandler(
                throwingExecutionService, sessionService, countingSender, directExecutor,
                Path.of("/tmp/chatops").toAbsolutePath(), 10,
                failingEngine
            );

            ChatOpsEvent event = new ChatOpsEvent("evt-secret", "msg-secret", "chat-secret", "user-secret", "fail me", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
            testHandler.handle(event);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            // Poll until the ERROR log event appears (ArrayList visibility across threads)
            List<String> logMessages = null;
            for (int i = 0; i < 50; i++) {
                logMessages = logAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
                if (logMessages.stream().anyMatch(msg -> msg.contains("LlmException"))) {
                    break;
                }
                Thread.sleep(50);
            }

            // Outbound message must not contain the raw secret
            assertThat(countingSender.getMessages()).allMatch(m -> !m.text().contains("sk-secret-123"));
            assertThat(countingSender.hasMessageContaining("Run failed")).isTrue();
            assertThat(countingSender.getMessages()).anyMatch(m -> m.text().contains("***"));

            // Logs must not contain the raw secret
            assertThat(logMessages).noneMatch(msg -> msg.contains("sk-secret-123"));
            assertThat(logMessages).anyMatch(msg -> msg.contains("***") && msg.contains("RuntimeException"));
        } finally {
            handlerLogger.detachAppender(logAppender);
        }
    }
}
