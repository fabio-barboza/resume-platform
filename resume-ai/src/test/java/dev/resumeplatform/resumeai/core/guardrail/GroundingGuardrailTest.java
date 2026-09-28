package dev.resumeplatform.resumeai.core.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.chat.ToolCall;
import dev.resumeplatform.resumeai.core.domain.chat.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GroundingGuardrailTest {
    static final String HALLUCINATED_ANSWER = """
            Para identificar o melhor candidato para a vaga de \
            Engenheiro de IA Aplicada, realizei uma busca semântica.

            1. **Lucas Mendes** — Engenharia de Machine Learning e MLOps.
            2. **Fernanda Lima** — Cientista de Dados Sênior com foco em Deep Learning.
            3. **Rafael Costa** — Visão Computacional e Análise Preditiva.
            """;

    static final String RATIONALIZED_ANSWER = "Fabio Barboza de Oliveira foi sim considerado, mas não "
            + "apareceu no top 3 por uma questão de peso semântico na busca automática.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static ChatMessage.Assistant ai(String text) {
        return new ChatMessage.Assistant(text);
    }

    static ChatMessage.Assistant toolCall(String name) {
        return new ChatMessage.Assistant("", List.of(new ToolCall("1", name, "{}")));
    }

    static ChatMessage.ToolResults toolResult(String name) {
        return new ChatMessage.ToolResults(List.of(new ToolResult("1", name, "...")));
    }

    static ChatMessage.User user(String text) {
        return new ChatMessage.User(text);
    }

    static ChatMessage.User retryCorrection() {
        return GroundingGuardrail.correction("Busque antes.");
    }

    static GroundingGuardrail.Verdict review(ChatMessage... messages) {
        return GroundingGuardrail.review(List.of(messages));
    }

    @Nested
    class SendsTheModelBackToSearch {
        @Test
        void inventedCandidates() {
            assertThat(review(user("Qual o melhor candidato para uma vaga de IA aplicada?"),
                    ai(HALLUCINATED_ANSWER)))
                    .as("era para mandar buscar antes de responder")
                    .isInstanceOf(GroundingGuardrail.Verdict.Retry.class);
        }

        @Test
        void answerFromMemory() {
            var verdict = review(user("Quem sabe Python?"), toolCall("find_in_resumes"),
                    toolResult("find_in_resumes"), ai("Encontrei Amanda Rocha."),
                    user("E o Fabio, por que não foi selecionado?"), ai(RATIONALIZED_ANSWER));
            assertThat(verdict).as("busca do turno anterior não fundamenta este turno")
                    .isInstanceOf(GroundingGuardrail.Verdict.Retry.class);
        }

        @Test
        void inventedChart() {
            String answer = "Distribuição:\n\n```chart\n{\"type\": \"bar\", \"data\": []}\n```";
            assertThat(review(user("Quantos por tecnologia?"), ai(answer)))
                    .isInstanceOf(GroundingGuardrail.Verdict.Retry.class);
        }

        @Test
        void handmadeResumeLink() {
            String answer = "Link para baixar o PDF: http://localhost:8000/candidates/12/resume";
            assertThat(review(user("Mostre o currículo deles"), ai(answer)))
                    .isInstanceOf(GroundingGuardrail.Verdict.Retry.class);
        }

        @Test
        void correctionIsMarkedAsRetry() {
            var verdict = (GroundingGuardrail.Verdict.Retry) review(user("Melhor candidato para IA?"),
                    ai(HALLUCINATED_ANSWER));
            assertThat(GroundingGuardrail.isRetryCorrection(verdict.correction())).isTrue();
        }
    }

    @Nested
    class GivesUpAfterTheSecondFailure {
        @Test
        void secondFailureDiscardsTheAnswer() {
            var verdict = review(user("Melhor candidato para IA?"), retryCorrection(),
                    ai(HALLUCINATED_ANSWER));
            assertThat(verdict).isInstanceOf(GroundingGuardrail.Verdict.GiveUp.class);
            assertThat(((GroundingGuardrail.Verdict.GiveUp) verdict).message()).contains("de memória");
        }

        @Test
        void retryThatSearchedPasses() {
            assertThat(review(user("Melhor candidato para IA?"), retryCorrection(),
                    toolCall("find_in_resumes"), toolResult("find_in_resumes"),
                    ai("**Larissa Moura** trabalha com machine learning.")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }
    }

    @Nested
    class LetsGroundedAnswerThrough {
        @Test
        void answerAfterToolCallPasses() {
            assertThat(review(user("O que você sabe sobre o Rafael Mendes?"),
                    toolCall("find_candidate_by_name"), toolResult("find_candidate_by_name"),
                    ai("**Rafael Mendes** é desenvolvedor backend.")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void messageWithToolCallsPasses() {
            assertThat(review(user("Melhor candidato para IA?"), toolCall("find_in_resumes")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void filenameAnnouncedAsPdfLinkIsBlocked() {
            var verdict = review(user("Melhores candidatos para a vaga de IA?"), toolCall("find_in_resumes"),
                    toolResult("find_in_resumes"),
                    ai("**Fabio Barboza de Oliveira**\nLink para baixar o PDF: curriculo_Fabio_Oliveira.pdf"));
            assertThat(verdict).as("nome de arquivo não é link e não pode passar")
                    .isInstanceOf(GroundingGuardrail.Verdict.Retry.class);
        }

        @Test
        void realPdfLinkPasses() {
            assertThat(review(user("Manda o currículo da Amanda"), toolCall("find_candidate_by_name"),
                    toolResult("find_candidate_by_name"),
                    ai("Encontrei: **Amanda Rocha**. Link para baixar o PDF: http://localhost:8000/candidates/1/resume")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void greetingPasses() {
            assertThat(review(user("oi, tudo bem?"),
                    ai("Olá! Busco currículos por tecnologia, senioridade ou nome.")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void apiInstructionsPass() {
            assertThat(review(user("Como cadastro alguém?"),
                    ai("Para enviar um currículo novo use `POST /resumes`, multipart, campo `files`. "
                            + "Abra o Swagger e use o botão Try it out.")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void protectedCriterionRefusalPasses() {
            String answer = "Não filtro candidatos por idade. Critério protegido: usá-lo para "
                    + "triagem é discriminação na contratação.\n\n"
                    + "O que dá para responder é o equivalente por competência: *quem tem "
                    + "até 3 anos de experiência?* — quer que eu busque assim?";
            assertThat(review(user("Quem tem menos de 30 anos?"), ai(answer)))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }

        @Test
        void technicalTermsAreNotNames() {
            assertThat(review(user("O que dá para buscar?"),
                    ai("Posso buscar por Machine Learning, Deep Learning, Visão Computacional ou "
                            + "Arquitetura de Software. Qual interessa?")))
                    .isInstanceOf(GroundingGuardrail.Verdict.Pass.class);
        }
    }

    @Nested
    class DegenerateChart {
        static final String FENCE = "```chart";

        String answer(String data) {
            return "Texto que responde sozinho.\n\n" + FENCE + "\n{\"type\": \"bar\", \"data\": " + data + "}\n```";
        }

        JsonNode chartOf(String text) {
            Matcher m = Pattern.compile("```chart\\n(.*?)\\n```", Pattern.DOTALL).matcher(text);
            assertThat(m.find()).isTrue();
            return JSON.readTree(m.group(1));
        }

        @Test
        void singleCategoryIsRemoved() {
            assertThat(GroundingGuardrail.stripDegenerateCharts(answer("[{\"label\": \"React\", \"value\": 3}]")))
                    .doesNotContain(FENCE);
        }

        @Test
        void zeroedVariationsAreNotCategories() {
            assertThat(GroundingGuardrail.stripDegenerateCharts(answer("[{\"label\": \"React\", \"value\": 3}, "
                    + "{\"label\": \"React.js\", \"value\": 0}, {\"label\": \"ReactJS\", \"value\": 0}]")))
                    .doesNotContain(FENCE);
        }

        @Test
        void zeroSlicesAreDroppedAndTheRestStays() {
            String pruned = GroundingGuardrail.stripDegenerateCharts(answer("[{\"label\": \"Python\", \"value\": 8}, "
                    + "{\"label\": \"Java\", \"value\": 5}, {\"label\": \"MLOps\", \"value\": 3}, "
                    + "{\"label\": \"GenAI\", \"value\": 0}, {\"label\": \"CrewAI\", \"value\": 0}]"));
            assertThat(pruned).contains(FENCE);
            List<String> labels = new ArrayList<>();
            chartOf(pruned).get("data").forEach(item -> labels.add(item.get("label").asString()));
            assertThat(labels).containsExactly("Python", "Java", "MLOps");
        }

        @Test
        void pruningKeepsTheRestOfTheJson() {
            String text = "Texto.\n\n" + FENCE + "\n{\"type\": \"pie\", \"title\": \"Aderência à vaga\", "
                    + "\"data\": [{\"label\": \"Fabio\", \"value\": 9}, {\"label\": \"Larissa\", \"value\": 6}, "
                    + "{\"label\": \"Ana\", \"value\": 0}]}\n```";
            JsonNode chart = chartOf(GroundingGuardrail.stripDegenerateCharts(text));
            assertThat(chart.get("type").asString()).isEqualTo("pie");
            assertThat(chart.get("title").asString()).isEqualTo("Aderência à vaga");
            assertThat(chart.get("data").size()).isEqualTo(2);
        }

        @Test
        void prunedJsonUsesPythonFormatting() {
            String pruned = GroundingGuardrail.stripDegenerateCharts(answer(
                    "[{\"label\": \"Python\", \"value\": 8}, {\"label\": \"Java\", \"value\": 5}, "
                            + "{\"label\": \"Go\", \"value\": 0}]"));
            assertThat(pruned).isEqualTo("""
                    Texto que responde sozinho.

                    ```chart
                    {
                      "type": "bar",
                      "data": [
                        {
                          "label": "Python",
                          "value": 8
                        },
                        {
                          "label": "Java",
                          "value": 5
                        }
                      ]
                    }
                    ```""");
        }

        @Test
        void realComparisonIsKept() {
            String text = answer("[{\"label\": \"Python\", \"value\": 8}, {\"label\": \"Java\", \"value\": 5}]");
            assertThat(GroundingGuardrail.stripDegenerateCharts(text)).isEqualTo(text);
        }

        @Test
        void answerTextSurvives() {
            assertThat(GroundingGuardrail.stripDegenerateCharts(answer("[{\"label\": \"React\", \"value\": 3}]")))
                    .isEqualTo("Texto que responde sozinho.");
        }

        @Test
        void invalidJsonStaysAsIs() {
            String text = "Texto.\n\n" + FENCE + "\nisso não é json\n```";
            assertThat(GroundingGuardrail.stripDegenerateCharts(text)).isEqualTo(text.stripTrailing());
        }

        @Test
        void guardrailRemovesTheChartWithoutAnotherRound() {
            var verdict = review(user("Quantos sabem React?"), toolCall("count_candidates_by_skill"),
                    toolResult("count_candidates_by_skill"), ai(answer("[{\"label\": \"React\", \"value\": 3}]")));
            assertThat(verdict).as("remover o gráfico não custa outra rodada")
                    .isInstanceOf(GroundingGuardrail.Verdict.Repair.class);
            assertThat(((GroundingGuardrail.Verdict.Repair) verdict).text()).doesNotContain(FENCE);
        }
    }
}
