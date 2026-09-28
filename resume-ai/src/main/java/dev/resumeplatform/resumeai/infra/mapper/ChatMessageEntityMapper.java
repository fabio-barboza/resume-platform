package dev.resumeplatform.resumeai.infra.mapper;

import java.util.List;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.domain.chat.ToolResult;
import dev.resumeplatform.resumeai.infra.entity.ChatMessageEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * As colunas JSON guardam a forma serializada de {@code AssistantMessage.ToolCall} e
 * {@code ToolResponseMessage.ToolResponse} do Spring AI; os records abaixo mantêm as mesmas chaves para que o
 * histórico já gravado continue legível.
 */
public final class ChatMessageEntityMapper {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FUNCTION = "function";

    record ToolCallJson(String id, String type, String name, String arguments) {
    }

    record ToolResponseJson(String id, String name, String responseData) {
    }

    private ChatMessageEntityMapper() {
    }

    public static ChatMessageEntity toEntity(String sessionId, ChatMessage message) {
        return switch (message) {
            case ChatMessage.User user -> new ChatMessageEntity(sessionId, ChatMessageEntity.USER, user.text(), null, null,
                    user.groundingRetry());
            case ChatMessage.Assistant assistant -> new ChatMessageEntity(sessionId, ChatMessageEntity.ASSISTANT,
                    assistant.text(), assistant.hasToolCalls() ? JSON.writeValueAsString(assistant.toolCalls().stream()
                            .map(c -> new ToolCallJson(c.id(), FUNCTION, c.name(), c.arguments())).toList()) : null,
                    null, false);
            case ChatMessage.ToolResults tool -> new ChatMessageEntity(sessionId, ChatMessageEntity.TOOL, "", null,
                    JSON.writeValueAsString(tool.results().stream()
                            .map(r -> new ToolResponseJson(r.id(), r.name(), r.content())).toList()),
                    false);
        };
    }

    public static ChatMessage toDomain(ChatMessageEntity row) {
        return switch (row.getMessageType()) {
            case ChatMessageEntity.USER -> new ChatMessage.User(row.getContent(), row.isGroundingRetry());
            case ChatMessageEntity.ASSISTANT -> new ChatMessage.Assistant(row.getContent(),
                    row.getToolCalls() == null ? List.of()
                            : JSON.readValue(row.getToolCalls(), new TypeReference<List<ToolCallJson>>() {
                            }).stream().map(c -> new ToolCall(c.id(), c.name(), c.arguments())).toList());
            case ChatMessageEntity.TOOL -> new ChatMessage.ToolResults(
                    JSON.readValue(row.getToolResponses(), new TypeReference<List<ToolResponseJson>>() {
                    }).stream().map(r -> new ToolResult(r.id(), r.name(), r.responseData())).toList());
            default -> throw new IllegalStateException("Tipo de mensagem desconhecido no histórico: " + row.getMessageType());
        };
    }
}
