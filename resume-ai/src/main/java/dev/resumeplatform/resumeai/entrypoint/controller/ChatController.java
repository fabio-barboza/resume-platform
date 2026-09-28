package dev.resumeplatform.resumeai.entrypoint.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.usecase.chat.AskAgentUseCase;
import dev.resumeplatform.resumeai.core.usecase.chat.GetChatHistoryUseCase;
import dev.resumeplatform.resumeai.entrypoint.controller.mapper.ResponseMapper;
import dev.resumeplatform.resumeai.entrypoint.controller.request.ChatRequest;
import dev.resumeplatform.resumeai.entrypoint.controller.response.ChatHistoryResponse;
import dev.resumeplatform.resumeai.entrypoint.controller.response.ChatResponse;
import dev.resumeplatform.resumeai.entrypoint.controller.sse.ChatEvent;
import dev.resumeplatform.resumeai.entrypoint.controller.sse.ChatStreamer;
import dev.resumeplatform.resumeai.entrypoint.controller.sse.ClientDisconnectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/chat")
@Tag(name = "Chat")
@Validated
public class ChatController {
    private final AskAgentUseCase askAgent;
    private final GetChatHistoryUseCase getChatHistory;
    private final ChatStreamer chatStreamer;
    private final JsonMapper json;

    public ChatController(AskAgentUseCase askAgent, GetChatHistoryUseCase getChatHistory, ChatStreamer chatStreamer,
            JsonMapper json) {
        this.askAgent = askAgent;
        this.chatStreamer = chatStreamer;
        this.getChatHistory = getChatHistory;
        this.json = json;
    }

    @PostMapping
    @Operation(summary = "Conversar com o agente de currículos", description = """
            Mesmo agente do stream, sem streaming. O histórico da conversa é \
            persistido no Postgres por `session_id`: sobrevive a um restart e é \
            compartilhado entre réplicas.""")
    public ChatResponse chat(@RequestBody @Validated ChatRequest payload) {
        return new ChatResponse(askAgent.execute(payload.sessionId(), payload.message(), AgentListener.NONE).content());
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Conversar com o agente com resposta em streaming (SSE)", description = """
            Mesmo agente de `POST /chat`, em `text/event-stream`. Cada frame é \
            `event: <tipo>\\ndata: <json>\\n\\n`. Tipos: `start` (`{session_id}`, sempre \
            primeiro), `tool` (`{name, status}`, início/fim de chamada de ferramenta), \
            `token` (`{text}`, delta de texto da resposta), `reset` (`{}`, descarte o \
            texto recebido até aqui — o guardrail reprovou a resposta e o modelo vai \
            recomeçar), `done` (`{content}`, fim normal, `content` é canônico) e `error` \
            (`{detail}`, falha — nunca acompanha `done`).""")
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody @Validated ChatRequest payload) {
        StreamingResponseBody body = out -> chatStreamer.stream(payload.sessionId(), payload.message(),
                event -> write(out, event));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())

                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping("/{session_id}")
    @Operation(summary = "Ler a conversa já persistida de uma sessão", description = """
            Devolve a conversa do `session_id` em ordem cronológica, só com as \
            falas do usuário e do agente. Sessão inexistente devolve lista vazia, \
            não 404: para o cliente é o mesmo caso de conversa ainda não iniciada.""")
    public ChatHistoryResponse chatHistory(@PathVariable("session_id") String sessionId) {
        return ResponseMapper.toResponse(sessionId, getChatHistory.execute(sessionId));
    }

    private void write(OutputStream out, ChatEvent event) {
        String frame = "event: " + event.type() + "\ndata: " + json.writeValueAsString(event.data()) + "\n\n";
        try {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ex) {
            throw new ClientDisconnectedException(ex);
        }
    }
}
