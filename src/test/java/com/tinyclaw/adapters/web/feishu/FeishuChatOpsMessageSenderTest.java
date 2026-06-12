package com.tinyclaw.adapters.web.feishu;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class FeishuChatOpsMessageSenderTest {

    @Test
    void sendTextDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "hello world"));
    }

    @Test
    void sendMessageDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", "prompt");
        assertThatNoException().isThrownBy(() -> sender.sendMessage("chat-1", msg));
    }

    @Test
    void sendTextWithSecretDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "api_key=sk-secret-123"));
    }

    @Test
    void sendMessageWithSecretDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runFailed("run-1", "password=super-secret");
        assertThatNoException().isThrownBy(() -> sender.sendMessage("chat-1", msg));
    }

    @Test
    void disabledSendTextDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(false);
        assertThatNoException().isThrownBy(() -> sender.sendText("chat-1", "hello"));
    }

    @Test
    void disabledSendMessageDoesNotThrow() {
        FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(false);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.thinking("run-1");
        assertThatNoException().isThrownBy(() -> sender.sendMessage("chat-1", msg));
    }

    @Test
    void sendTextWithSecretDoesNotLeakInLogs() {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(FeishuChatOpsMessageSender.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);

        try {
            FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
            sender.sendText("chat-1", "api_key=sk-secret-123");

            List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(logMessages).noneMatch(msg -> msg.contains("sk-secret-123"));
            assertThat(logMessages).anyMatch(msg -> msg.contains("***"));
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
            FeishuChatOpsMessageSender sender = new FeishuChatOpsMessageSender(true);
            ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runFailed("run-1", "password=super-secret");
            sender.sendMessage("chat-1", msg);

            List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(logMessages).noneMatch(m -> m.contains("super-secret"));
            assertThat(logMessages).anyMatch(m -> m.contains("***"));
        } finally {
            senderLogger.detachAppender(logAppender);
        }
    }
}
