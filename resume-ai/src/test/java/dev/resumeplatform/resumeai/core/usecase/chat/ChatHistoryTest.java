package dev.resumeplatform.resumeai.core.usecase.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ChatTurn;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.gateway.ChatModelGateway;
import dev.resumeplatform.resumeai.core.gateway.CriterionClassifierGateway;
import dev.resumeplatform.resumeai.infra.repository.ChatMessageRepository;
import dev.resumeplatform.resumeai.support.DatabaseTest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** O turno só chega ao banco inteiro e depois de terminar; o modelo é o único dublê. */
class ChatHistoryTest extends DatabaseTest {
    @MockitoBean
    ChatModelGateway model;
    @MockitoBean
    CriterionClassifierGateway classifier;

    @Autowired
    AskAgentUseCase askAgent;
    @Autowired
    GetChatHistoryUseCase getChatHistory;
    @Autowired
    ChatMessageRepository messages;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<List<ChatMessage>> contexts = new ArrayList<>();

    @BeforeEach
    void toolsAvailable() {
        when(model.toolNames()).thenReturn(Set.of("find_in_resumes", "count_candidates_by_skill"));
    }

    private void replies(ChatMessage.Assistant... answers) {
        var queue = new ArrayList<>(List.of(answers));
        when(model.reply(anyList(), any())).thenAnswer(invocation -> {
            contexts.add(List.copyOf(invocation.<List<ChatMessage>>getArgument(0)));
            return queue.removeFirst();
        });
    }

    private static ChatMessage.Assistant toolCall(String id, String name, String arguments) {
        return new ChatMessage.Assistant("", List.of(new ToolCall(id, name, arguments)));
    }

    private static String session() {
        return "s-" + UUID.randomUUID();
    }

    private void ask(String sessionId, String question) {
        askAgent.execute(sessionId, question, AgentListener.NONE);
    }

    @Test
    void historyAccumulatesAcrossTurns() {
        String id = session();
        replies(new ChatMessage.Assistant("primeira resposta"), new ChatMessage.Assistant("segunda resposta"));

        ask(id, "primeira pergunta");
        ask(id, "segunda pergunta");

        assertThat(messages.findBySessionIdOrderByIdAsc(id)).extracting(m -> m.getContent())
                .containsExactly("primeira pergunta", "primeira resposta", "segunda pergunta", "segunda resposta");
    }

    @Test
    void nextTurnReceivesPersistedHistory() {
        String id = session();
        replies(new ChatMessage.Assistant("resposta 1"), new ChatMessage.Assistant("resposta 2"));

        ask(id, "pergunta 1");
        ask(id, "pergunta 2");

        assertThat(contexts.getLast()).extracting(ChatMessage::text)
                .containsExactly("pergunta 1", "resposta 1", "pergunta 2");
    }

    @Test
    void historyPreservedAfterException() {
        String id = session();
        replies(new ChatMessage.Assistant("resposta ok"));
        ask(id, "pergunta 1");
        int before = messages.findBySessionIdOrderByIdAsc(id).size();

        doThrow(new IllegalStateException("falha")).when(model).reply(anyList(), any());
        assertThatThrownBy(() -> ask(id, "pergunta 2")).isInstanceOf(IllegalStateException.class);

        assertThat(messages.findBySessionIdOrderByIdAsc(id)).hasSize(before);
    }

    @Test
    void orphanQuestionNotStoredWhenTheTurnIsInterrupted() {
        String id = session();
        replies(toolCall("c1", "find_in_resumes", "{}"), new ChatMessage.Assistant("fim"));
        when(model.callTool(anyString(), anyString())).thenReturn("trecho");
        AgentListener disconnecting = new AgentListener() {
            @Override
            public void onToolStart(String name) {
                throw new IllegalStateException("cliente desconectou");
            }
        };

        assertThatThrownBy(() -> askAgent.execute(id, "pergunta abandonada", disconnecting))
                .isInstanceOf(IllegalStateException.class);

        assertThat(messages.findBySessionIdOrderByIdAsc(id)).isEmpty();
    }

    @Test
    void screenHistoryOmitsMachinery() {
        String id = session();
        replies(new ChatMessage.Assistant("Recomendo Fulano de Tal."), toolCall("c1", "find_in_resumes", "{}"),
                new ChatMessage.Assistant("Encontrei Diego Santana."));
        when(model.callTool(anyString(), anyString())).thenReturn("trecho");

        ask(id, "quem sabe Python?");

        assertThat(messages.findBySessionIdOrderByIdAsc(id)).as("tool call, resultado e correção ficam gravados")
                .hasSize(5);
        assertThat(getChatHistory.execute(id)).containsExactly(new ChatTurn("user", "quem sabe Python?"),
                new ChatTurn("assistant", "Encontrei Diego Santana."));
    }

    @Test
    void toolCallsRoundTripThroughTheDatabase() {
        String id = session();
        var call = new ToolCall("c1", "count_candidates_by_skill", "{\"skills\":[\"Go\"]}");
        replies(new ChatMessage.Assistant("", List.of(call)), new ChatMessage.Assistant("ok"),
                new ChatMessage.Assistant("x"));
        when(model.callTool(anyString(), anyString())).thenReturn("- Go: 2");

        ask(id, "q");

        var stored = messages.findBySessionIdOrderByIdAsc(id);
        assertThat(JSON.readValue(stored.get(1).getToolCalls(), new TypeReference<List<Map<String, String>>>() {
        })).as("mesmas chaves do AssistantMessage.ToolCall do Spring AI, que gravou o histórico antigo")
                .containsExactly(Map.of("id", "c1", "type", "function", "name", "count_candidates_by_skill",
                        "arguments", "{\"skills\":[\"Go\"]}"));
        assertThat(JSON.readValue(stored.get(2).getToolResponses(), new TypeReference<List<Map<String, String>>>() {
        })).containsExactly(Map.of("id", "c1", "name", "count_candidates_by_skill", "responseData", "- Go: 2"));

        ask(id, "q2");

        var history = contexts.getLast();
        assertThat(((ChatMessage.Assistant) history.get(1)).toolCalls()).containsExactly(call);
        assertThat(((ChatMessage.ToolResults) history.get(2)).results().getFirst().content()).isEqualTo("- Go: 2");
    }
}
