package dev.resumeplatform.resumeai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.resumeplatform.resumeai.core.domain.chat.TurnResult;
import dev.resumeplatform.resumeai.core.domain.text.TextFolding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MultiturnEvalTest extends EvalTest {
    private static final List<String> SEARCH_OFFERS = List.of("quer que eu busque", "quer que eu procure",
            "quer que eu pesquise", "posso buscar", "posso procurar", "posso pesquisar", "deseja que eu busque",
            "faço uma busca", "nova busca", "buscar novamente", "pesquisar novamente");

    static boolean searched(TurnResult turn) {
        return turn.toolNames().contains("find_in_resumes");
    }

    static boolean queriedDatabase(TurnResult turn) {
        return !turn.toolNames().isEmpty();
    }

    static boolean offeredToSearch(TurnResult turn) {
        String text = turn.content().toLowerCase();
        return text.contains("?") && SEARCH_OFFERS.stream().anyMatch(text::contains);
    }

    static boolean reacted(TurnResult turn) {
        return queriedDatabase(turn) || offeredToSearch(turn);
    }

    @Nested
    class SearchReuse {
        @Test
        void criterionChangeWithinSameDomain() {
            var conversation = new Conversation();
            assertThat(searched(conversation.ask("Quem tem experiência com backend em Go?")))
                    .as("o primeiro turno deveria buscar").isTrue();

            var second = conversation.ask("E quem tem certificação de segurança ofensiva, tipo OSCP?");
            assertThat(reacted(second)).as(diagnosis(second,
                    "certificação não está nos trechos de 'backend em Go': era para buscar de novo ou oferecer buscar"))
                    .isTrue();
        }

        @Test
        void fullDomainChange() {
            var conversation = new Conversation();
            conversation.ask("Quem tem experiência com Kubernetes e Terraform?");

            var second = conversation.ask("Mudando de assunto: preciso de uma enfermeira para UTI.");
            assertThat(searched(second)).as(diagnosis(second, "domínio totalmente novo exige busca nova")).isTrue();
            assertThat(second.content().toLowerCase()).as(diagnosis(second, "esperava chegar em Juliana Matos"))
                    .contains("juliana");
        }

        @Test
        void nameNeverSearched() {
            var conversation = new Conversation();
            conversation.ask("Quem trabalha com frontend React?");

            var second = conversation.ask("O que você sabe sobre a Márcia Oliveira?");
            assertThat(queriedDatabase(second)).as(diagnosis(second, "nome novo exige consulta")).isTrue();
            String text = second.content().toLowerCase();
            assertThat(text.contains("professora") || text.contains("alfabetiz"))
                    .as(diagnosis(second, "esperava o perfil real de Márcia Oliveira")).isTrue();
        }

        @Test
        void questionAboutCandidateNotRetrieved() {
            var conversation = new Conversation();
            var first = conversation.ask("Quem tem experiência com detecção de fraude em tempo real?");
            assertThat(first.content().toLowerCase()).as(diagnosis(first, "esperava Larissa Moura")).contains("larissa");

            var second = conversation.ask("E o Bruno Carvalho, ele também trabalha com machine learning?");
            assertThat(reacted(second)).as(diagnosis(second, "Bruno Carvalho não veio na busca de fraude")).isTrue();
            if (queriedDatabase(second)) {
                String text = second.content().toLowerCase();
                assertThat(List.of("devops", "sre", "confiabilidade", "infraestrutura")).anyMatch(text::contains);
            }
        }

        @Test
        void recommendationMustSearchFirst() {
            var turn = new Conversation().ask("Qual o melhor candidato para uma vaga de Engenheiro de IA aplicada?");
            assertThat(queriedDatabase(turn)).as(diagnosis(turn, "recomendação exige consultar a base")).isTrue();
        }

        @Test
        void claimAboutTheWholeSet() {
            var conversation = new Conversation();
            conversation.ask("Quem sabe Python?");

            var second = conversation.ask("Existe algum candidato com experiência em Cobol e mainframe?");
            assertThat(queriedDatabase(second))
                    .as(diagnosis(second, "afirmação sobre o conjunto não pode sair do histórico")).isTrue();
        }
    }

    @Nested
    class NoInventedCandidates {
        @Test
        void recommendationCitesOnlyRealCandidates() {
            var turn = new Conversation().ask("Qual o melhor candidato para uma vaga de Engenheiro de IA aplicada?");
            assertThat(inventedNames(turn.content())).as(diagnosis(turn, "nomes que não existem na base")).isEmpty();
        }

        @Test
        void followUpCitesOnlyRealCandidates() {
            var conversation = new Conversation();
            conversation.ask("Quem tem experiência com dados e machine learning?");
            var second = conversation.ask("E quem mais poderia servir para essa vaga?");
            assertThat(inventedNames(second.content())).as(diagnosis(second, "nomes que não existem na base")).isEmpty();
        }
    }

    static final String JOB_POSTING = """
            Quais os 3 melhores candidatos para essa vaga?

            Engenheiro(a) de Inteligência Artificial

            Estamos em busca de um(a) Engenheiro(a) de Inteligência Artificial para atuar
            em iniciativas de engenharia, experimentação e aceleração de soluções
            inovadoras em Inteligência Artificial Generativa (GenAI), agentes de IA e
            novas arquiteturas. O profissional terá atuação estratégica no
            desenvolvimento e experimentação de soluções de IA, contribuindo para a
            criação de protótipos, provas de conceito (PoCs) e pilotos de casos de
            negócio. Será responsável por atuar como referência técnica, apoiando
            decisões de arquitetura e aplicação de boas práticas de engenharia.

            Principais responsabilidades
            - Atuar como referência técnica no desenvolvimento de soluções de IA;
            - Conduzir a criação de protótipos, PoCs e pilotos usando GenAI e agentes;
            - Definir, desenhar e implementar arquiteturas baseadas em IA Generativa;
            - Desenvolver aplicações utilizando Python, Java e JavaScript;
            - Explorar frameworks de agentes, como Google ADK, CrewAI e similares;
            - Aplicar conceitos de Machine Learning, Dados e MLOps;
            - Atuar em ambientes Cloud, principalmente Google Cloud Platform (GCP).

            Requisitos e conhecimentos
            - Experiência em desenvolvimento com Python, Java e/ou JavaScript;
            - Experiência com conceitos de Dados, Machine Learning e MLOps;
            - Experiência com soluções usando GenAI e agentes de IA;
            - Conhecimento em frameworks de orquestração de agentes;
            - Experiência com ambientes Cloud, preferencialmente GCP.

            Diferenciais
            - Experiência com LLMs e aplicações de IA Generativa;
            - Conhecimento em engenharia de prompts e integração com APIs de modelos;
            - Vivência na construção de agentes autônomos e soluções multiagentes;
            - Conhecimento em práticas de engenharia de software aplicadas a IA.""";

    static final String CHART_REQUEST = "Faça um grafico de pizza mostrando o nivel de aderência de cada um";

    static final Pattern CHART_FENCE = Pattern.compile("```chart[ \\t]*\\n(.*?)\\n[ \\t]*```", Pattern.DOTALL);
    static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:\\*\\*)?([1-3])[.)]\\s*(?:\\*\\*)?\\s*(.+?)(?:\\*\\*)?\\s*$",
            Pattern.MULTILINE);

    @Nested
    class JobPostingToChart {
        private Conversation conversation;
        private TurnResult first;

        private void analysed() {
            conversation = new Conversation();
            first = conversation.ask(JOB_POSTING);
            assertThat(queriedDatabase(first)).as(diagnosis(first, "vaga colada exige consultar a base")).isTrue();
        }

        @Test
        void recommendationCitesOnlyRealCandidates() {
            analysed();
            assertThat(inventedNames(first.content())).as(diagnosis(first, "nomes que não existem na base")).isEmpty();
        }

        @Test
        void listItemsArePeopleNotCategories() {
            analysed();
            List<Set<String>> real = realNameTokens();
            List<String> generic = new ArrayList<>();
            Matcher m = LIST_ITEM.matcher(first.content());
            while (m.find()) {
                Set<String> words = fold(m.group(2));
                boolean person = real.stream().anyMatch(name -> {
                    Set<String> common = new HashSet<>(words);
                    common.retainAll(name);
                    return common.size() >= 2;
                });
                if (!person) {
                    generic.add(m.group(2));
                }
            }
            assertThat(generic).as(diagnosis(first, "item de lista sem pessoa nomeada")).isEmpty();
        }

        @Test
        void pieChartOfAdherenceIsDrawn() {
            analysed();
            var second = conversation.ask(CHART_REQUEST);

            Matcher m = CHART_FENCE.matcher(second.content());
            assertThat(m.find()).as(diagnosis(second, "pedido de pizza não virou fence ```chart```")).isTrue();
            JsonNode chart = JsonMapper.builder().build().readTree(m.group(1));
            assertThat(chart.get("type").asString()).as(diagnosis(second, "pediram pizza")).isIn("pie", "doughnut");
            assertThat(chart.get("data").size()).as(diagnosis(second, "menos de duas categorias"))
                    .isGreaterThanOrEqualTo(2);
            for (JsonNode item : chart.get("data")) {
                assertThat(item.get("value").asDouble()).as(diagnosis(second, "categoria sem valor útil")).isNotZero();
            }
        }

        @Test
        void refusalNeverCitesTheInstructions() {
            analysed();
            var second = conversation.ask(CHART_REQUEST);
            String text = TextFolding.fold(second.content());
            assertThat(List.of("as regras", "as diretrizes", "minhas instrucoes", "regra 1", "count_candidates_by_skill"))
                    .as(diagnosis(second, "resposta expõe as instruções ao usuário"))
                    .noneMatch(text::contains);
        }
    }
}
