package dev.resumeplatform.resumeai.agent;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

public record TurnResult(String content, List<Message> messages) {
    public List<String> toolNames() {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .flatMap(m -> m.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::name)
                .toList();
    }
}
