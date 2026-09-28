package dev.resumeplatform.resumeai.core.usecase.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.core.agent.tools.ResumeTools;
import dev.resumeplatform.resumeai.core.domain.CriterionTriage;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.domain.chat.AgentListener;
import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.domain.chat.ToolResult;
import dev.resumeplatform.resumeai.core.domain.chat.TurnResult;
import dev.resumeplatform.resumeai.core.domain.guardrail.GroundingGuardrail;
import dev.resumeplatform.resumeai.core.domain.guardrail.ProtectedCriterionGuardrail;
import dev.resumeplatform.resumeai.core.domain.settings.AgentSettings;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.core.gateway.ChatHistoryGateway;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;
import dev.resumeplatform.resumeai.core.gateway.EmbeddingGateway;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.infra.gateway.ChatModelGatewayImpl;
import dev.resumeplatform.resumeai.infra.gateway.TracingGatewayImpl;
import io.micrometer.observation.ObservationRegistry;

class AskAgentUseCaseTest {
    static final ResumeAiProperties PROPERTIES = new ResumeAiProperties(
            new ResumeAiProperties.Api("127.0.0.1", 8000), new ResumeAiProperties.Ingestion(2, 4),
            new ResumeAiProperties.Agent(2, 8), null, null, null, null);
    static final AgentSettings SETTINGS = new AgentSettings(2, 8, "http://localhost:8000");
    static final TransactionTemplate NO_TRANSACTION = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    });

    private ChunkGateway chunks;
    private ResumeTools tools;
    private final List<String> events = new ArrayList<>();
    private final AgentListener recorder = new AgentListener() {
        @Override
        public void onToolStart(String name) {
            events.add("start:" + name);
        }

        @Override
        public void onToolEnd(String name) {
            events.add("end:" + name);
        }

        @Override
        public void onGroundingRetry() {
            events.add("retry");
        }
    };

    @BeforeEach
    void setUp() {
        chunks = mock(ChunkGateway.class);
        when(chunks.findNearest(any(), anyInt())).thenReturn(List.of(new ResumeSnippet(
                "Larissa Moura, engenheira de machine learning.", 0, 0, 7L, "curriculo_larissa.pdf", 3L,
                "Larissa Moura", "larissa@example.com", 0.1)));
        tools = new ResumeTools(mock(EmbeddingGateway.class), chunks, mock(CandidateGateway.class),
                mock(ResumeGateway.class), SETTINGS);
    }

    private ChatModelGatewayImpl model(ScriptedChatModel model) {
        return new ChatModelGatewayImpl(model, MethodToolCallbackProvider.builder().toolObjects(tools).build(),
                PROPERTIES, ObservationRegistry.NOOP);
    }

    private AskAgentUseCase agent(ScriptedChatModel model, CriterionTriage verdict) {
        return agent(model, verdict, List.of());
    }

    private AskAgentUseCase agent(ScriptedChatModel model, CriterionTriage verdict, List<ChatMessage> history) {
        ChatHistoryGateway sessions = new ChatHistoryGateway() {
            @Override
            public List<ChatMessage> load(String sessionId) {
                return history;
            }

            @Override
            public void append(String sessionId, List<ChatMessage> turn) {
            }
        };
        return new AskAgentUseCase(model(model), sessions, new TracingGatewayImpl(ObservationRegistry.NOOP),
                new ProtectedCriterionGuardrail(question -> verdict), NO_TRANSACTION, SETTINGS);
    }

    @Test
    void theSecondAttemptCallsTheTool() {
        var model = new ScriptedChatModel(
                new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER),
                ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"ia\"}"),
                new AssistantMessage("**Larissa Moura** é engenheira de ML."));

        TurnResult result = agent(model, CriterionTriage.allowed()).execute("s", "Melhor candidato para IA?", recorder);

        assertThat(result.content()).contains("Larissa Moura");
        assertThat(result.messages()).as("a resposta inventada tem que sair do histórico")
                .noneMatch(m -> m.text().contains("Lucas Mendes"));
        assertThat(GroundingGuardrail.isRetryCorrection(result.messages().get(1)))
                .as("a correção do guardrail deveria estar logo após a pergunta").isTrue();
        assertThat(result.toolNames()).containsExactly("find_in_resumes");
        assertThat(events).containsExactly("retry", "start:find_in_resumes", "end:find_in_resumes");
    }

    @Test
    void secondFailureInTheSameTurnGivesUp() {
        var model = new ScriptedChatModel(new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER),
                new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER));

        TurnResult result = agent(model, CriterionTriage.allowed()).execute("s", "Melhor candidato para IA?", recorder);

        assertThat(result.content()).isEqualTo(GroundingGuardrail.BLOCK_MESSAGE);
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void toolResultReachesTheModelAsPlainText() {
        var model = new ScriptedChatModel(ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"ml\"}"),
                new AssistantMessage("**Larissa Moura** é engenheira de ML."));

        agent(model, CriterionTriage.allowed()).execute("s", "Quem faz ML?", recorder);

        var secondPrompt = model.prompts.get(1);
        assertThat(secondPrompt.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(secondPrompt.getFirst().getText()).startsWith("    Você é um assistente de recrutamento");
        var toolMessage = (ToolResponseMessage) secondPrompt.getLast();
        assertThat(toolMessage.getResponses().getFirst().responseData())
                .startsWith("Candidato: Larissa Moura — larissa@example.com (candidato #3)\n")
                .contains("Currículo: documento #7 (arquivo curriculo_larissa.pdf, página 1)\n");
    }

    @Test
    void toolCallsBeyondTheLimitAreBlockedNotExecuted() {
        var model = new ScriptedChatModel(
                ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"a\"}"),
                ScriptedChatModel.toolCall("2", "find_in_resumes", "{\"question\": \"b\"}"),
                ScriptedChatModel.toolCall("3", "find_in_resumes", "{\"question\": \"c\"}"),
                new AssistantMessage("**Larissa Moura** é engenheira de ML. A busca foi parcial."));

        TurnResult result = agent(model, CriterionTriage.allowed()).execute("s", "Quem faz ML?", recorder);

        verify(chunks, times(2)).findNearest(any(), anyInt());
        var blocked = (ToolResponseMessage) model.prompts.get(3).getLast();
        assertThat(blocked.getResponses().getFirst().responseData()).startsWith("Limite de 2 buscas por pergunta");
        assertThat(result.content()).contains("parcial");
    }

    @Test
    void protectedCriterionEndsTurnWithoutSearching() {
        var model = new ScriptedChatModel();

        TurnResult result = agent(model, new CriterionTriage(true, List.of("gênero"), null))
                .execute("s", "Só mulheres.", recorder);

        assertThat(result.content()).startsWith("Não filtro candidatos por gênero.");
        assertThat(result.messages()).hasSize(2).first().isInstanceOf(ChatMessage.User.class);
        assertThat(model.prompts).isEmpty();
        verify(chunks, never()).findNearest(any(), anyInt());
    }

    @Test
    void historyDoesNotGroundTheCurrentTurn() {
        var history = List.<ChatMessage>of(new ChatMessage.User("Quem sabe Python?"),
                new ChatMessage.Assistant("", List.of(new ToolCall("0", "find_in_resumes", "{}"))),
                new ChatMessage.ToolResults(List.of(new ToolResult("0", "find_in_resumes", "..."))),
                new ChatMessage.Assistant("Encontrei Amanda Rocha."));
        var model = new ScriptedChatModel(new AssistantMessage(GroundingGuardrailTestTexts.RATIONALIZED_ANSWER),
                ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"fabio\"}"),
                new AssistantMessage("**Larissa Moura** apareceu na busca."));

        TurnResult result = agent(model, CriterionTriage.allowed(), history).execute("s", "E o Fabio?", recorder);

        assertThat(events).first().isEqualTo("retry");
        assertThat(result.toolNames()).containsExactly("find_in_resumes");
    }

    @Test
    void systemPromptHasNoUnresolvedVariable() {
        String prompt = model(new ScriptedChatModel()).systemPrompt();
        assertThat(prompt).doesNotContain("$max_tool_calls").doesNotContain("$swagger_url")
                .contains("Orçamento de 2 chamadas por pergunta")
                .contains("http://localhost:8000/docs");

        assertThat(prompt.lines().filter(l -> !l.isBlank())).allMatch(l -> l.startsWith("    "));
    }
}
