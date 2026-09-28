package dev.resumeplatform.resumeai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.agent.AgentListener;
import dev.resumeplatform.resumeai.agent.ResumeAgent;
import dev.resumeplatform.resumeai.agent.TurnResult;
import dev.resumeplatform.resumeai.db.ChatMessage;
import dev.resumeplatform.resumeai.db.ChatMessageRepository;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String STREAM_FAILURE = "Falha ao gerar a resposta. Tente novamente.";

    private final ResumeAgent agent;
    private final ChatMessageRepository messages;
    private final TransactionTemplate transaction;

    public ChatService(ResumeAgent agent, ChatMessageRepository messages, TransactionTemplate transaction) {
        this.agent = agent;
        this.messages = messages;
        this.transaction = transaction;
    }

    public record Turn(String role, String content) {
    }

    @Transactional(readOnly = true)
    public List<Turn> history(String sessionId) {
        List<Turn> turns = new ArrayList<>();
        for (ChatMessage row : messages.findBySessionIdOrderByIdAsc(sessionId)) {
            if (row.getContent() == null || row.getContent().isEmpty()) {
                continue;
            }
            if (ChatMessage.USER.equals(row.getMessageType()) && !row.isGroundingRetry()) {
                turns.add(new Turn("user", row.getContent()));
            } else if (ChatMessage.ASSISTANT.equals(row.getMessageType()) && row.getToolCalls() == null) {
                turns.add(new Turn("assistant", row.getContent()));
            }
        }
        return turns;
    }

    public TurnResult ask(String sessionId, String message) {
        return runTurn(sessionId, message, AgentListener.NONE);
    }

    public void stream(String sessionId, String message, Consumer<ChatEvent> sink) {
        sink.accept(ChatEvent.start(sessionId));

        StringBuilder streamed = new StringBuilder();
        AgentListener listener = new AgentListener() {
            @Override
            public void onToolStart(String name) {
                sink.accept(ChatEvent.tool(name, "start"));
            }

            @Override
            public void onToolEnd(String name) {
                sink.accept(ChatEvent.tool(name, "end"));
            }

            @Override
            public void onToken(String text) {
                streamed.append(text);
                sink.accept(ChatEvent.token(text));
            }

            @Override
            public void onGroundingRetry() {
                if (!streamed.isEmpty()) {
                    streamed.setLength(0);
                    sink.accept(ChatEvent.reset());
                }
            }
        };

        TurnResult result;
        try {
            result = runTurn(sessionId, message, listener);
        } catch (ClientDisconnectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Falha ao gerar a resposta em streaming (session_id={}).", sessionId, ex);
            sink.accept(ChatEvent.error(STREAM_FAILURE));
            return;
        }

        String content = result.content() == null ? "" : result.content();

        if (!content.isEmpty() && !streamed.toString().equals(content)) {
            sink.accept(ChatEvent.reset());
            sink.accept(ChatEvent.token(content));
        }
        sink.accept(ChatEvent.done(content));
    }

    private TurnResult runTurn(String sessionId, String message, AgentListener listener) {
        List<Message> history = transaction.execute(tx -> messages.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ConversationMapper::toMessage)
                .toList());
        TurnResult result = agent.run(history, message, listener);
        transaction.executeWithoutResult(tx -> messages.saveAll(
                result.messages().stream().map(m -> ConversationMapper.toEntity(sessionId, m)).toList()));
        return result;
    }

    public static class ClientDisconnectedException extends RuntimeException {
        public ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
