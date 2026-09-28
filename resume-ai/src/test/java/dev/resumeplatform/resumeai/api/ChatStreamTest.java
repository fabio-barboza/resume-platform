package dev.resumeplatform.resumeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.resumeplatform.resumeai.agent.AgentListener;
import dev.resumeplatform.resumeai.agent.ResumeAgent;
import dev.resumeplatform.resumeai.agent.TurnResult;
import dev.resumeplatform.resumeai.db.ChatMessageRepository;
import dev.resumeplatform.resumeai.guardrail.GroundingGuardrail;
import dev.resumeplatform.resumeai.service.ChatEvent;
import dev.resumeplatform.resumeai.service.ChatService;
import dev.resumeplatform.resumeai.support.DatabaseTest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class ChatStreamTest extends DatabaseTest {
    @MockitoBean
    ResumeAgent agent;

    @Autowired
    MockMvc mvc;
    @Autowired
    ChatService chatService;
    @Autowired
    ChatMessageRepository messages;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    interface Step {
        void play(AgentListener listener);
    }

    static Step token(String text) {
        return l -> l.onToken(text);
    }

    static Step tool(String name) {
        return l -> {
            l.onToolStart(name);
            l.onToolEnd(name);
        };
    }

    static Step retry() {
        return AgentListener::onGroundingRetry;
    }

    private void script(String finalContent, Step... steps) {
        doAnswer(invocation -> {
            String question = invocation.getArgument(1);
            AgentListener listener = invocation.getArgument(2);
            for (Step step : steps) {
                step.play(listener);
            }
            return new TurnResult(finalContent, List.of(new UserMessage(question), new AssistantMessage(finalContent)));
        }).when(agent).run(anyList(), anyString(), any());
    }

    private static String session() {
        return "s-" + UUID.randomUUID();
    }

    private String streamBody(String sessionId, String message) throws Exception {
        MvcResult started = mvc.perform(post("/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("session_id", sessionId, "message", message))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started)).andReturn();
        assertThat(done.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(done.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        return done.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    record Frame(String event, Map<String, Object> data) {
    }

    static List<Frame> parse(String body) {
        List<Frame> frames = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            if (frame.isBlank()) {
                continue;
            }
            String[] lines = frame.split("\n");
            assertThat(lines).as("frame com forma inesperada: %s", frame).hasSize(2);
            assertThat(lines[0]).startsWith("event: ");
            assertThat(lines[1]).startsWith("data: ");
            frames.add(new Frame(lines[0].substring("event: ".length()),
                    JSON.readValue(lines[1].substring("data: ".length()), new TypeReference<Map<String, Object>>() {
                    })));
        }
        return frames;
    }

    static String rendered(List<Frame> frames) {
        StringBuilder buffer = new StringBuilder();
        for (Frame frame : frames) {
            if (frame.event().equals("reset")) {
                buffer.setLength(0);
            } else if (frame.event().equals("token")) {
                buffer.append(frame.data().get("text"));
            }
        }
        return buffer.toString();
    }

    @Nested
    class StreamShape {
        @Test
        void orderAndShape() throws Exception {
            script("Olá, mundo", token("Olá"), token(", mundo"));

            var frames = parse(streamBody("s1", "oi"));

            assertThat(frames.getFirst()).isEqualTo(new Frame("start", Map.of("session_id", "s1")));
            assertThat(frames.getLast().event()).isEqualTo("done");
        }

        @Test
        void concatenatedTokensEqualDone() throws Exception {
            script("parte 1 parte 2", token("parte 1 "), token("parte 2"));

            var frames = parse(streamBody(session(), "oi"));

            String tokens = frames.stream().filter(f -> f.event().equals("token"))
                    .map(f -> (String) f.data().get("text")).reduce("", String::concat);
            assertThat(tokens).isEqualTo(frames.getLast().data().get("content"));
            assertThat(frames).noneMatch(f -> f.event().equals("reset"));
        }

        @Test
        void fallbackWithoutTokens() throws Exception {
            script("Não filtro por esse critério.");

            var frames = parse(streamBody(session(), "oi"));

            assertThat(frames).filteredOn(f -> f.event().equals("token")).extracting(f -> f.data().get("text"))
                    .containsExactly("Não filtro por esse critério.");
            assertThat(frames.getLast()).isEqualTo(new Frame("done", Map.of("content", "Não filtro por esse critério.")));
        }

        @Test
        void toolEventsDoNotLeakContent() throws Exception {
            script("Encontrei o candidato.", tool("find_candidate_by_name"), token("Encontrei o candidato."));

            var frames = parse(streamBody(session(), "oi"));

            assertThat(frames).filteredOn(f -> f.event().equals("tool")).extracting(Frame::data).containsExactly(
                    Map.of("name", "find_candidate_by_name", "status", "start"),
                    Map.of("name", "find_candidate_by_name", "status", "end"));
        }

        @Test
        void answerRejectedByGroundingNeverReachesTheScreen() throws Exception {
            script("Encontrei Diego Santana.", token("Recomendo Fulano de Tal"), token(" e Beltrano da Silva."),
                    retry(), token("Encontrei"), token(" Diego Santana."));

            var frames = parse(streamBody(session(), "oi"));

            assertThat(frames).as("sem reset, a 2ª resposta cola na 1ª").contains(new Frame("reset", Map.of()));
            assertThat(rendered(frames)).isEqualTo("Encontrei Diego Santana.");
        }

        @Test
        void answerSwappedForRefusalIsAlsoCorrected() throws Exception {
            script("Não consigo responder sem consultar a base.", token("Recomendo Fulano de Tal."));

            var frames = parse(streamBody(session(), "oi"));

            assertThat(rendered(frames)).isEqualTo("Não consigo responder sem consultar a base.");
        }

        @Test
        void failureEmitsErrorAndNeverDone() throws Exception {
            doAnswer(invocation -> {
                AgentListener listener = invocation.getArgument(2);
                listener.onToken("começando...");
                throw new IllegalStateException("falha simulada do provedor");
            }).when(agent).run(anyList(), anyString(), any());

            var frames = parse(streamBody(session(), "oi"));

            assertThat(frames.getLast()).isEqualTo(new Frame("error",
                    Map.of("detail", "Falha ao gerar a resposta. Tente novamente.")));
            assertThat(frames).noneMatch(f -> f.event().equals("done"));
        }
    }

    @Nested
    class History {
        @Test
        void historyAccumulatesAcrossTurns() throws Exception {
            String id = session();
            script("primeira resposta", token("primeira resposta"));
            streamBody(id, "primeira pergunta");
            script("segunda resposta");
            streamBody(id, "segunda pergunta");

            assertThat(messages.findBySessionIdOrderByIdAsc(id)).extracting(m -> m.getContent())
                    .containsExactly("primeira pergunta", "primeira resposta", "segunda pergunta", "segunda resposta");
        }

        @Test
        void nextTurnReceivesPersistedHistory() {
            String id = session();
            script("resposta 1");
            chatService.ask(id, "pergunta 1");

            List<List<Message>> seen = new ArrayList<>();
            doAnswer(invocation -> {
                seen.add(invocation.getArgument(0));
                return new TurnResult("resposta 2", List.of(new UserMessage("pergunta 2"), new AssistantMessage("resposta 2")));
            }).when(agent).run(anyList(), anyString(), any());
            chatService.ask(id, "pergunta 2");

            assertThat(seen.getFirst()).extracting(Message::getText).containsExactly("pergunta 1", "resposta 1");
        }

        @Test
        void historyPreservedAfterException() throws Exception {
            String id = session();
            script("resposta ok");
            streamBody(id, "pergunta 1");
            var before = messages.findBySessionIdOrderByIdAsc(id).size();

            doThrow(new IllegalStateException("falha")).when(agent).run(anyList(), anyString(), any());
            streamBody(id, "pergunta 2");

            assertThat(messages.findBySessionIdOrderByIdAsc(id)).hasSize(before);
        }

        @Test
        void orphanQuestionNotStoredOnDisconnect() {
            String id = session();
            script("fim", tool("find_in_resumes"), token("fim"));
            List<ChatEvent> received = new ArrayList<>();

            try {
                chatService.stream(id, "pergunta abandonada", event -> {
                    received.add(event);
                    if (event.type().equals("tool")) {
                        throw new ChatService.ClientDisconnectedException(new java.io.IOException("broken pipe"));
                    }
                });
            } catch (ChatService.ClientDisconnectedException expected) {
            }

            assertThat(received).extracting(ChatEvent::type).containsExactly("start", "tool");
            assertThat(messages.findBySessionIdOrderByIdAsc(id)).isEmpty();
        }

        @Test
        void screenHistoryOmitsMachinery() throws Exception {
            String id = session();
            doReturn(new TurnResult("Encontrei Diego Santana.", List.of(
                    new UserMessage("quem sabe Python?"),
                    AssistantMessage.builder().content("Vou buscar na base.")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "find_in_resumes", "{}")))
                            .build(),
                    ToolResponseMessage.builder().responses(
                            List.of(new ToolResponseMessage.ToolResponse("c1", "find_in_resumes", "trecho"))).build(),
                    GroundingGuardrail.correction("Correção automática do sistema, não do usuário: ..."),
                    new AssistantMessage("Encontrei Diego Santana.")))).when(agent).run(anyList(), anyString(), any());
            chatService.ask(id, "quem sabe Python?");

            String body = mvc.perform(get("/chat/" + id)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);

            assertThat(JSON.readTree(body).get("session_id").asString()).isEqualTo(id);
            assertThat(JSON.readValue(JSON.readTree(body).get("messages").toString(),
                    new TypeReference<List<Map<String, String>>>() {
                    })).containsExactly(Map.of("role", "user", "content", "quem sabe Python?"),
                            Map.of("role", "assistant", "content", "Encontrei Diego Santana."));
        }

        @Test
        void toolCallsRoundTripThroughTheDatabase() {
            String id = session();
            var call = new AssistantMessage.ToolCall("c1", "function", "count_candidates_by_skill", "{\"skills\":[\"Go\"]}");
            doReturn(new TurnResult("ok", List.of(
                    new UserMessage("q"), AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                    ToolResponseMessage.builder().responses(
                            List.of(new ToolResponseMessage.ToolResponse("c1", "count_candidates_by_skill", "- Go: 2")))
                            .build(),
                    new AssistantMessage("ok")))).when(agent).run(anyList(), anyString(), any());
            chatService.ask(id, "q");

            List<List<Message>> seen = new ArrayList<>();
            doAnswer(invocation -> {
                seen.add(invocation.getArgument(0));
                return new TurnResult("x", List.of());
            }).when(agent).run(anyList(), anyString(), any());
            chatService.ask(id, "q2");

            var history = seen.getFirst();
            assertThat(((AssistantMessage) history.get(1)).getToolCalls()).containsExactly(call);
            assertThat(((ToolResponseMessage) history.get(2)).getResponses().getFirst().responseData())
                    .isEqualTo("- Go: 2");
        }

        @Test
        void unknownSessionReturnsEmptyList() throws Exception {
            String body = mvc.perform(get("/chat/nunca-usada")).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(JSON.readTree(body).get("messages").size()).isZero();
        }
    }

    @Test
    void postChatReturnsContent() throws Exception {
        script("resposta sem streaming");

        String body = mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\": \"" + session() + "\", \"message\": \"oi\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JSON.readTree(body).get("content").asString()).isEqualTo("resposta sem streaming");
    }

    @Test
    void missingFieldIsUnprocessable() throws Exception {
        var response = mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"oi\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).has("detail")).isTrue();
    }
}
