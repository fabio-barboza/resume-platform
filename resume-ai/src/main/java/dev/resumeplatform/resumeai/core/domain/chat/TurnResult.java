package dev.resumeplatform.resumeai.core.domain.chat;

import java.util.List;

public record TurnResult(String content, List<ChatMessage> messages) {
    public List<String> toolNames() {
        return messages.stream()
                .filter(ChatMessage.Assistant.class::isInstance)
                .map(ChatMessage.Assistant.class::cast)
                .flatMap(m -> m.toolCalls().stream())
                .map(ToolCall::name)
                .toList();
    }
}
