package dev.resumeplatform.resumeai.infra.gateway.mapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;

public final class SpringAiMessageMapper {
    private static final String FUNCTION = "function";

    private SpringAiMessageMapper() {
    }

    public static Message toSpringAi(ChatMessage message) {
        return switch (message) {
            case ChatMessage.User user -> new UserMessage(user.text());
            case ChatMessage.Assistant assistant -> AssistantMessage.builder()
                    .content(assistant.text())
                    .toolCalls(assistant.toolCalls().stream()
                            .map(c -> new AssistantMessage.ToolCall(c.id(), FUNCTION, c.name(), c.arguments()))
                            .toList())
                    .build();
            case ChatMessage.ToolResults tool -> ToolResponseMessage.builder()
                    .responses(tool.results().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.content()))
                            .toList())
                    .build();
        };
    }

    public static ToolCall toDomain(AssistantMessage.ToolCall call) {
        return new ToolCall(call.id(), call.name(), call.arguments());
    }
}
