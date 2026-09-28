package dev.resumeplatform.resumeai.core.usecase.chat;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ChatTurn;
import dev.resumeplatform.resumeai.core.gateway.ChatHistoryGateway;

@Service
public class GetChatHistoryUseCase {
    private final ChatHistoryGateway history;

    public GetChatHistoryUseCase(ChatHistoryGateway history) {
        this.history = history;
    }

    @Transactional(readOnly = true)
    public List<ChatTurn> execute(String sessionId) {
        List<ChatTurn> turns = new ArrayList<>();
        for (ChatMessage message : history.load(sessionId)) {
            if (message.text() == null || message.text().isEmpty()) {
                continue;
            }
            switch (message) {
                case ChatMessage.User user when !user.groundingRetry() -> turns.add(new ChatTurn("user", user.text()));
                case ChatMessage.Assistant assistant when !assistant.hasToolCalls() ->
                        turns.add(new ChatTurn("assistant", assistant.text()));
                default -> {
                }
            }
        }
        return turns;
    }
}
