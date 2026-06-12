package com.tinyclaw.adapters.web.feishu;

import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import org.junit.jupiter.api.Test;

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
}
