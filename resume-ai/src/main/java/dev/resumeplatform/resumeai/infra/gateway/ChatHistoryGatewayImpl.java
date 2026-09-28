package dev.resumeplatform.resumeai.infra.gateway;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.gateway.ChatHistoryGateway;
import dev.resumeplatform.resumeai.infra.repository.ChatMessageRepository;
import dev.resumeplatform.resumeai.infra.repository.mapper.ChatMessageEntityMapper;

@Component
public class ChatHistoryGatewayImpl implements ChatHistoryGateway {
    private final ChatMessageRepository messages;

    public ChatHistoryGatewayImpl(ChatMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        return messages.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ChatMessageEntityMapper::toDomain)
                .toList();
    }

    @Override
    public void append(String sessionId, List<ChatMessage> turn) {
        messages.saveAll(turn.stream().map(m -> ChatMessageEntityMapper.toEntity(sessionId, m)).toList());
    }
}
