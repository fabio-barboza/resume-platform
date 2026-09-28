package dev.resumeplatform.resumeai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.ai.chat.messages.UserMessage;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.db.SimilarChunkRow;
import dev.resumeplatform.resumeai.guardrail.CriterionTriage;
import dev.resumeplatform.resumeai.guardrail.GroundingGuardrail;
import dev.resumeplatform.resumeai.guardrail.ProtectedCriterionGuardrail;
import dev.resumeplatform.resumeai.service.CandidateService;
import dev.resumeplatform.resumeai.service.DocumentService;
import dev.resumeplatform.resumeai.service.VectorSearchService;
import io.micrometer.observation.ObservationRegistry;

class ResumeAgentTest {
    static final ResumeAiProperties PROPERTIES = new ResumeAiProperties(
            new ResumeAiProperties.Api("127.0.0.1", 8000), new ResumeAiProperties.Ingestion(2, 4),
            new ResumeAiProperties.Agent(2, 8), null, null, null, null);

    private VectorSearchService vectorSearch;
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
        vectorSearch = mock(VectorSearchService.class);
        when(vectorSearch.similaritySearch(anyString(), anyInt())).thenReturn(List.of(new SimilarChunkRow(
                "Larissa Moura, engenheira de machine learning.", 0, 0, 7L, "curriculo_larissa.pdf", 3L,
                "Larissa Moura", "larissa@example.com", 0.1)));
        tools = new ResumeTools(vectorSearch, mock(CandidateService.class), mock(DocumentService.class), PROPERTIES);
    }

    private ResumeAgent agent(ScriptedChatModel model, CriterionTriage verdict) {
        return new ResumeAgent(model, tools, new ProtectedCriterionGuardrail(question -> verdict), PROPERTIES,
                ObservationRegistry.NOOP);
    }

    @Test
    void theSecondAttemptCallsTheTool() {
        var model = new ScriptedChatModel(
                new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER),
                ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"ia\"}"),
                new AssistantMessage("**Larissa Moura** é engenheira de ML."));

        TurnResult result = agent(model, CriterionTriage.allowed()).run(List.of(), "Melhor candidato para IA?", recorder);

        assertThat(result.content()).contains("Larissa Moura");
        assertThat(result.messages()).as("a resposta inventada tem que sair do histórico")
                .noneMatch(m -> m.getText() != null && m.getText().contains("Lucas Mendes"));
        assertThat(GroundingGuardrail.isRetryCorrection(result.messages().get(1)))
                .as("a correção do guardrail deveria estar logo após a pergunta").isTrue();
        assertThat(result.toolNames()).containsExactly("find_in_resumes");
        assertThat(events).containsExactly("retry", "start:find_in_resumes", "end:find_in_resumes");
    }

    @Test
    void secondFailureInTheSameTurnGivesUp() {
        var model = new ScriptedChatModel(new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER),
                new AssistantMessage(GroundingGuardrailTestTexts.HALLUCINATED_ANSWER));

        TurnResult result = agent(model, CriterionTriage.allowed()).run(List.of(), "Melhor candidato para IA?", recorder);

        assertThat(result.content()).isEqualTo(GroundingGuardrail.BLOCK_MESSAGE);
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void toolResultReachesTheModelAsPlainText() {
        var model = new ScriptedChatModel(ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"ml\"}"),
                new AssistantMessage("**Larissa Moura** é engenheira de ML."));

        agent(model, CriterionTriage.allowed()).run(List.of(), "Quem faz ML?", recorder);

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

        TurnResult result = agent(model, CriterionTriage.allowed()).run(List.of(), "Quem faz ML?", recorder);

        verify(vectorSearch, times(2)).similaritySearch(anyString(), anyInt());
        var blocked = (ToolResponseMessage) model.prompts.get(3).getLast();
        assertThat(blocked.getResponses().getFirst().responseData()).startsWith("Limite de 2 buscas por pergunta");
        assertThat(result.content()).contains("parcial");
    }

    @Test
    void protectedCriterionEndsTurnWithoutSearching() {
        var model = new ScriptedChatModel();

        TurnResult result = agent(model, new CriterionTriage(true, List.of("gênero"), null))
                .run(List.of(), "Só mulheres.", recorder);

        assertThat(result.content()).startsWith("Não filtro candidatos por gênero.");
        assertThat(result.messages()).hasSize(2).first().isInstanceOf(UserMessage.class);
        assertThat(model.prompts).isEmpty();
        verify(vectorSearch, never()).similaritySearch(anyString(), anyInt());
    }

    @Test
    void historyDoesNotGroundTheCurrentTurn() {
        var history = List.<org.springframework.ai.chat.messages.Message>of(new UserMessage("Quem sabe Python?"),
                ScriptedChatModel.toolCall("0", "find_in_resumes", "{}"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("0", "find_in_resumes", "...")))
                        .build(),
                new AssistantMessage("Encontrei Amanda Rocha."));
        var model = new ScriptedChatModel(new AssistantMessage(GroundingGuardrailTestTexts.RATIONALIZED_ANSWER),
                ScriptedChatModel.toolCall("1", "find_in_resumes", "{\"question\": \"fabio\"}"),
                new AssistantMessage("**Larissa Moura** apareceu na busca."));

        TurnResult result = agent(model, CriterionTriage.allowed()).run(history, "E o Fabio?", recorder);

        assertThat(events).first().isEqualTo("retry");
        assertThat(result.toolNames()).containsExactly("find_in_resumes");
    }

    @Test
    void systemPromptHasNoUnresolvedVariable() {
        var model = new ScriptedChatModel();
        String prompt = agent(model, CriterionTriage.allowed()).systemPrompt();
        assertThat(prompt).doesNotContain("$max_tool_calls").doesNotContain("$swagger_url")
                .contains("Orçamento de 2 chamadas por pergunta")
                .contains("http://localhost:8000/docs");

        assertThat(prompt.lines().filter(l -> !l.isBlank())).allMatch(l -> l.startsWith("    "));
    }
}
