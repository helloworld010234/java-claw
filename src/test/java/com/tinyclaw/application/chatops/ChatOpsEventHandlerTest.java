package com.tinyclaw.application.chatops;

import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatOpsEventHandlerTest {

    private AgentRunExecutionService executionService;
    private SessionService sessionService;
    private FakeChatOpsMessageSender sender;
    private ChatOpsEventHandler handler;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentRunExecutionService.class);
        sessionService = mock(SessionService.class);
        sender = new FakeChatOpsMessageSender();
        handler = new ChatOpsEventHandler(
            executionService,
            sessionService,
            sender,
            Executors.newSingleThreadExecutor(),
            Path.of("/tmp/chatops").toAbsolutePath(),
            10
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
    void validTextMessageTriggersRun() {
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 2, null));

        ChatOpsEvent event = new ChatOpsEvent("evt-1", "msg-1", "chat-1", "user-1", "hello world", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        assertThat(handler.handle(event)).isTrue();

        // Wait for async execution
        sleepBriefly();

        verify(executionService).execute(any(), any(), eq("hello world"), any(), isNull(), eq("chatops"), isNull(), eq(10));
        assertThat(sender.hasMessageContaining("Run started")).isTrue();
        assertThat(sender.hasMessageContaining("Run completed")).isTrue();
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
    void runIdIsPresentInContext() {
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        ChatOpsEvent event = new ChatOpsEvent("evt-2", "msg-2", "chat-2", "user-2", "run test", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        handler.handle(event);
        sleepBriefly();

        var captor = org.mockito.ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(executionService).execute(any(), any(), any(), captor.capture(), isNull(), eq("chatops"), isNull(), anyInt());
        ToolExecutionContext ctx = captor.getValue();
        assertThat(ctx.runId()).isNotNull().isNotBlank();
        assertThat(ctx.sessionId()).isEqualTo("chatops-chat-2");
        assertThat(ctx.workspaceRoot().toString()).contains("chatops");
    }

    @Test
    void failedRunSendsFailureMessage() {
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(false, "", 1, "engine error"));

        ChatOpsEvent event = new ChatOpsEvent("evt-3", "msg-3", "chat-3", "user-3", "fail me", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        handler.handle(event);
        sleepBriefly();

        assertThat(sender.hasMessageContaining("Run failed")).isTrue();
        assertThat(sender.hasMessageContaining("engine error")).isTrue();
    }

    @Test
    void exceptionInRunSendsFailureMessage() {
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenThrow(new RuntimeException("boom"));

        ChatOpsEvent event = new ChatOpsEvent("evt-4", "msg-4", "chat-4", "user-4", "explode", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        handler.handle(event);
        sleepBriefly();

        assertThat(sender.hasMessageContaining("Run failed")).isTrue();
        assertThat(sender.hasMessageContaining("boom")).isTrue();
    }

    @Test
    void promptIsNormalizedTrimmed() {
        when(executionService.execute(any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new AgentRunResult(true, "Done", 1, null));

        ChatOpsEvent event = new ChatOpsEvent("evt-5", "msg-5", "chat-5", "user-5", "  hello world  ", Instant.now(), ChatOpsEvent.Type.TEXT_MESSAGE);
        handler.handle(event);
        sleepBriefly();

        verify(executionService).execute(any(), any(), eq("hello world"), any(), isNull(), eq("chatops"), isNull(), anyInt());
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
