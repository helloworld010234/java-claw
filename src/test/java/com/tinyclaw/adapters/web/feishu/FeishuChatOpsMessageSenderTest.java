package com.tinyclaw.adapters.web.feishu;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.config.ChatOpsProperties;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuChatOpsMessageSenderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendTextSuccess() {
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        sender.sendText("chat-1", "hello world");

        assertThat(transport.tokenCalls.get()).isEqualTo(1);
        assertThat(transport.messageCalls.get()).isEqualTo(1);
        assertThat(transport.lastMessageBody).contains("\"msg_type\":\"text\"");
        assertThat(transport.lastMessageBody).contains("\"receive_id\":\"chat-1\"");
        assertThat(transport.lastMessageBody).contains("\\\"text\\\":\\\"hello world\\\"");
    }

    @Test
    void sendMessageSuccess() {
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", "prompt preview");

        sender.sendMessage("chat-1", msg);

        assertThat(transport.messageCalls.get()).isEqualTo(1);
        assertThat(transport.lastMessageBody).contains("[RUN_STARTED]");
        assertThat(transport.lastMessageBody).contains("prompt preview");
    }

    @Test
    void sendTextDoesNotThrowOnFeishuError() {
        FakeTransport transport = new FakeTransport("token-1", 99991670, "bad request");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "hello"));
        assertThat(transport.messageCalls.get()).isEqualTo(1);
    }

    @Test
    void sendTextRetriesOnceOnTokenErrorThenSucceeds() {
        FakeTransport transport = new FakeTransport(List.of("token-1", "token-2"), "msg-1");
        transport.forceTokenErrorOnFirstSend = true;
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        sender.sendText("chat-1", "hello");

        assertThat(transport.tokenCalls.get()).isEqualTo(2);
        assertThat(transport.messageCalls.get()).isEqualTo(2);
    }

    @Test
    void sendTextDoesNotRetryMoreThanOnceOnTokenError() {
        FakeTransport transport = new FakeTransport(List.of("token-1", "token-2"), "msg-1");
        transport.forceTokenErrorOnFirstSend = true;
        transport.forceTokenErrorAlways = true;
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        sender.sendText("chat-1", "hello");

        assertThat(transport.tokenCalls.get()).isEqualTo(2);
        assertThat(transport.messageCalls.get()).isEqualTo(2);
    }

    @Test
    void sendTextDoesNotThrowWhenTokenFetchFails() {
        FakeTransport transport = new FakeTransport(List.<String>of(), "msg-1");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "hello"));
        assertThat(transport.messageCalls.get()).isZero();
    }

    @Test
    void disabledSendTextDoesNotCallNetwork() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setEnabled(false);
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createSender(properties, transport);

        sender.sendText("chat-1", "hello");

        assertThat(transport.tokenCalls.get()).isZero();
        assertThat(transport.messageCalls.get()).isZero();
    }

    @Test
    void enabledButUnconfiguredSendTextDoesNotCallNetwork() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setEnabled(true);
        // appId/appSecret left blank
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createSender(properties, transport);

        sender.sendText("chat-1", "hello");

        assertThat(transport.tokenCalls.get()).isZero();
        assertThat(transport.messageCalls.get()).isZero();
    }

    @Test
    void disabledSendMessageDoesNotCallNetwork() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setEnabled(false);
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createSender(properties, transport);

        sender.sendMessage("chat-1", ChatOpsOutboundMessage.thinking("run-1"));

        assertThat(transport.tokenCalls.get()).isZero();
        assertThat(transport.messageCalls.get()).isZero();
    }

    @Test
    void enabledButUnconfiguredSendMessageDoesNotCallNetwork() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setEnabled(true);
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createSender(properties, transport);

        sender.sendMessage("chat-1", ChatOpsOutboundMessage.thinking("run-1"));

        assertThat(transport.tokenCalls.get()).isZero();
        assertThat(transport.messageCalls.get()).isZero();
    }

    @Test
    void sendTextRetriesOnceOnOtherTokenErrorCode() {
        FakeTransport transport = new FakeTransport(List.of("token-1", "token-2"), "msg-1");
        transport.forceTokenErrorOnFirstSend = true;
        transport.forceTokenErrorCode = 99991661;
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        sender.sendText("chat-1", "hello");

        assertThat(transport.tokenCalls.get()).isEqualTo(2);
        assertThat(transport.messageCalls.get()).isEqualTo(2);
    }

    @Test
    void sendTextHandlesUnexpectedExceptionDuringSend() {
        FakeTransport transport = new FakeTransport("token-1", "msg-1") {
            @Override
            public String post(String uri, Map<String, String> headers, Object body) {
                if (uri.contains("im/v1/messages")) {
                    throw new RuntimeException("network explosion");
                }
                return super.post(uri, headers, body);
            }
        };
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);

        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "hello"));
    }

    @Test
    void constructorRejectsNullDependencies() {
        ChatOpsProperties properties = new ChatOpsProperties();
        FeishuTenantAccessTokenProvider tokenProvider = new FeishuTenantAccessTokenProvider(
            "https://open.feishu.cn", "dummy", "dummy", 300,
            new FakeTransport("token-1", "msg-1"), OBJECT_MAPPER
        );
        FeishuMessageApiClient client = new FeishuMessageApiClient(
            "https://open.feishu.cn", new FakeTransport("token-1", "msg-1"), OBJECT_MAPPER
        );

        assertThatThrownBy(() -> new FeishuChatOpsMessageSender(null, tokenProvider, client))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeishuChatOpsMessageSender(properties, null, client))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeishuChatOpsMessageSender(properties, tokenProvider, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendTextWithSecretDoesNotLeakInLogs() {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);

        try {
            FakeTransport transport = new FakeTransport("token-1", "msg-1");
            FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
            sender.sendText("chat-1", "api_key=sk-secret-123");

            List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(logMessages).noneMatch(msg -> msg.contains("sk-secret-123"));
            assertThat(logMessages).noneMatch(msg -> msg.contains("api_key=sk-secret-123"));
            assertThat(transport.lastMessageBody).contains("***");
        } finally {
            senderLogger.detachAppender(logAppender);
        }
    }

    @Test
    void sendMessageWithSecretDoesNotLeakInLogs() {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);

        try {
            FakeTransport transport = new FakeTransport("token-1", "msg-1");
            FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
            ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runFailed("run-1", "password=super-secret");
            sender.sendMessage("chat-1", msg);

            List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(logMessages).noneMatch(m -> m.contains("super-secret"));
            assertThat(logMessages).noneMatch(m -> m.contains("password=super-secret"));
            assertThat(transport.lastMessageBody).contains("***");
        } finally {
            senderLogger.detachAppender(logAppender);
        }
    }

    @Test
    void sendTextSuccessLogDoesNotContainBody() {
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
        String body = "customer email alice@example.com";

        List<String> logMessages = captureSenderLogs(() -> sender.sendText("chat-1", body));

        assertThat(logMessages).noneMatch(m -> m.contains(body));
        assertThat(logMessages).noneMatch(m -> m.contains("alice@example.com"));
        assertThat(logMessages).anyMatch(m -> m.contains("chat-1"));
        assertThat(logMessages).anyMatch(m -> m.contains("msg-1"));
        assertThat(logMessages).anyMatch(m -> m.contains("type=TEXT"));
    }

    @Test
    void sendMessageSuccessLogDoesNotContainBody() {
        FakeTransport transport = new FakeTransport("token-1", "msg-1");
        FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted(
            "run-1", "internal file content api_key=sk-secret-123"
        );

        List<String> logMessages = captureSenderLogs(() -> sender.sendMessage("chat-1", msg));

        assertThat(logMessages).noneMatch(m -> m.contains("internal file content"));
        assertThat(logMessages).noneMatch(m -> m.contains("api_key=sk-secret-123"));
        assertThat(logMessages).noneMatch(m -> m.contains("sk-secret-123"));
        assertThat(logMessages).anyMatch(m -> m.contains("chat-1"));
        assertThat(logMessages).anyMatch(m -> m.contains("msg-1"));
        assertThat(logMessages).anyMatch(m -> m.contains("type=RUN_STARTED"));
        assertThat(transport.lastMessageBody).contains("***");
    }

    @Test
    void tokenAndSecretDoNotAppearInLogs() {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);

        try {
            FakeTransport transport = new FakeTransport("tenant-token-xyz", "msg-1");
            FeishuChatOpsMessageSender sender = createConfiguredSender(transport);
            sender.sendText("chat-1", "api_key=sk-secret-123 app_secret=top-secret");

            String allLogs = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);
            assertThat(allLogs).doesNotContain("tenant-token-xyz");
            assertThat(allLogs).doesNotContain("top-secret");
            assertThat(allLogs).doesNotContain("sk-secret-123");
        } finally {
            senderLogger.detachAppender(logAppender);
        }
    }

    private List<String> captureSenderLogs(Runnable action) {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
        try {
            action.run();
            return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        } finally {
            senderLogger.detachAppender(logAppender);
        }
    }

    private FeishuChatOpsMessageSender createConfiguredSender(FakeTransport transport) {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setEnabled(true);
        properties.setAppId("app-id");
        properties.setAppSecret("app-secret");
        return createSender(properties, transport);
    }

    private FeishuChatOpsMessageSender createSender(ChatOpsProperties properties, FakeTransport transport) {
        FeishuTenantAccessTokenProvider tokenProvider = new FeishuTenantAccessTokenProvider(
            "https://open.feishu.cn",
            properties.getAppId(),
            properties.getAppSecret(),
            300,
            transport,
            OBJECT_MAPPER,
            Clock.systemUTC()
        );
        FeishuMessageApiClient messageApiClient = new FeishuMessageApiClient(
            "https://open.feishu.cn",
            transport,
            OBJECT_MAPPER
        );
        return new FeishuChatOpsMessageSender(properties, tokenProvider, messageApiClient);
    }

    private static class FakeTransport implements FeishuHttpTransport {

        private final List<String> tokens;
        private final String messageId;
        private final int errorCode;
        private final String errorMsg;
        private final int tokenExpireSeconds;

        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger messageCalls = new AtomicInteger();
        volatile String lastMessageBody;
        volatile boolean forceTokenErrorOnFirstSend = false;
        volatile boolean forceTokenErrorAlways = false;
        volatile int forceTokenErrorCode = 99991663;

        FakeTransport(String token, String messageId) {
            this(List.of(token), messageId, 0, null, 7200);
        }

        FakeTransport(String token, String messageId, int errorCode, String errorMsg) {
            this(List.of(token), messageId, errorCode, errorMsg, 7200);
        }

        FakeTransport(String token, int errorCode, String errorMsg) {
            this(List.of(token), null, errorCode, errorMsg, 7200);
        }

        FakeTransport(List<String> tokens, String messageId) {
            this(tokens, messageId, 0, null, 7200);
        }

        FakeTransport(List<String> tokens, String messageId, int errorCode, String errorMsg) {
            this(tokens, messageId, errorCode, errorMsg, 7200);
        }

        FakeTransport(List<String> tokens, String messageId, int errorCode, String errorMsg, int tokenExpireSeconds) {
            this.tokens = tokens != null ? new ArrayList<>(tokens) : new ArrayList<>();
            this.messageId = messageId;
            this.errorCode = errorCode;
            this.errorMsg = errorMsg;
            this.tokenExpireSeconds = tokenExpireSeconds;
        }

        @Override
        public String post(String uri, Map<String, String> headers, Object body) {
            if (uri.contains("tenant_access_token/internal")) {
                tokenCalls.incrementAndGet();
                if (tokens.isEmpty()) {
                    throw new FeishuApiException("token fetch failed");
                }
                return tokenResponse(tokens.removeFirst());
            }

            messageCalls.incrementAndGet();
            try {
                lastMessageBody = OBJECT_MAPPER.writeValueAsString(body);
            } catch (Exception e) {
                lastMessageBody = "";
            }

            boolean tokenError = forceTokenErrorAlways || (forceTokenErrorOnFirstSend && messageCalls.get() == 1);
            if (tokenError) {
                return errorResponse(forceTokenErrorCode, "token expired");
            }
            if (errorCode != 0) {
                return errorResponse(errorCode, errorMsg);
            }
            return successMessageResponse();
        }

        private String tokenResponse(String token) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "code", 0,
                    "msg", "ok",
                    "tenant_access_token", token,
                    "expire", tokenExpireSeconds
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private String successMessageResponse() {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "code", 0,
                    "msg", "ok",
                    "data", Map.of("message_id", messageId)
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private String errorResponse(int code, String msg) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of("code", code, "msg", msg));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
