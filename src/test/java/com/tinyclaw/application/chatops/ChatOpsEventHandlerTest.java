package com.tinyclaw.application.chatops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentContextBuilder;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.engine.ToolFailureRecoveryAdvisor;
import com.tinyclaw.application.engine.WorkingMemorySelector;
import com.tinyclaw.application.engine.ContextCompactor;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
}
