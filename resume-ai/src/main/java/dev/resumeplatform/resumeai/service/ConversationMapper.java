package dev.resumeplatform.resumeai.service;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import dev.resumeplatform.resumeai.db.ChatMessage;
import dev.resumeplatform.resumeai.guardrail.GroundingGuardrail;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class ConversationMapper {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConversationMapper() {
    }

    static ChatMessage toEntity(String sessionId, Message message) {
        return switch (message) {
            case UserMessage user -> new ChatMessage(sessionId, ChatMessage.USER, user.getText(), null, null,
                    GroundingGuardrail.isRetryCorrection(user));
            case AssistantMessage assistant -> new ChatMessage(sessionId, ChatMessage.ASSISTANT, assistant.getText(),
                    assistant.hasToolCalls() ? JSON.writeValueAsString(assistant.getToolCalls()) : null, null, false);
            case ToolResponseMessage tool -> new ChatMessage(sessionId, ChatMessage.TOOL, "", null,
                    JSON.writeValueAsString(tool.getResponses()), false);
            default -> throw new IllegalArgumentException("Tipo de mensagem não persistível: " + message.getMessageType());
        };
    }

    static Message toMessage(ChatMessage row) {
        return switch (row.getMessageType()) {
            case ChatMessage.USER -> UserMessage.builder()
                    .text(row.getContent())
                    .metadata(row.isGroundingRetry() ? Map.of(GroundingGuardrail.RETRY_FLAG, true) : Map.of())
                    .build();
            case ChatMessage.ASSISTANT -> AssistantMessage.builder()
                    .content(row.getContent())
                    .toolCalls(row.getToolCalls() == null ? List.of()
                            : JSON.readValue(row.getToolCalls(), new TypeReference<List<AssistantMessage.ToolCall>>() {
                            }))
                    .build();
            case ChatMessage.TOOL -> ToolResponseMessage.builder()
                    .responses(JSON.readValue(row.getToolResponses(),
                            new TypeReference<List<ToolResponseMessage.ToolResponse>>() {
                            }))
                    .build();
            default -> throw new IllegalStateException("Tipo de mensagem desconhecido no histórico: " + row.getMessageType());
        };
    }
}
