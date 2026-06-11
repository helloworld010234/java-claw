package com.tinyclaw.ports.chatops;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory fake implementation of {@link ChatOpsMessageSender} for testing.
 */
public class FakeChatOpsMessageSender implements ChatOpsMessageSender {

    private final List<RecordedMessage> messages = new ArrayList<>();

    @Override
    public void sendText(String chatId, String text) {
        messages.add(new RecordedMessage(chatId, text, null));
    }

    @Override
    public void sendMessage(String chatId, ChatOpsOutboundMessage message) {
        messages.add(new RecordedMessage(chatId, message.text(), message));
    }

    public List<RecordedMessage> getMessages() {
        return List.copyOf(messages);
    }

    public void clear() {
        messages.clear();
    }

    public boolean hasMessageContaining(String substring) {
        return messages.stream().anyMatch(m -> m.text != null && m.text.contains(substring));
    }

    public record RecordedMessage(String chatId, String text, ChatOpsOutboundMessage message) {}
}
