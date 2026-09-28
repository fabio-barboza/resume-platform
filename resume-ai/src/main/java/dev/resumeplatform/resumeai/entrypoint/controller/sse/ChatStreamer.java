package dev.resumeplatform.resumeai.entrypoint.controller.sse;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.domain.chat.TurnResult;
import dev.resumeplatform.resumeai.core.usecase.chat.AskAgentUseCase;

/**
 * Traduz o turno do agente nos eventos SSE que o {@code sse.js} da webui parseia: {@code start}, {@code tool},
 * {@code token}, {@code reset} (o grounding descartou a resposta), {@code done} ou {@code error}.
 */
@Component
public class ChatStreamer {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);

    static final String STREAM_FAILURE = "Falha ao gerar a resposta. Tente novamente.";

    private final AskAgentUseCase askAgent;

    public ChatStreamer(AskAgentUseCase askAgent) {
        this.askAgent = askAgent;
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
            result = askAgent.execute(sessionId, message, listener);
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
}
