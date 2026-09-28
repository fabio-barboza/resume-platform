package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;

public interface ChatHistoryGateway {
    List<ChatMessage> load(String sessionId);

    void append(String sessionId, List<ChatMessage> messages);
}
