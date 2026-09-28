package dev.resumeplatform.resumeai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.core.domain.CandidateWithResume;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.usecase.candidate.SearchCandidatesByNameUseCase;
import dev.resumeplatform.resumeai.core.usecase.search.SearchSimilarChunksUseCase;

class RetrievalEvalTest extends EvalTest {
    private static final int OVERFETCH = 6;
    private static final double MIN_RECALL = 0.80;

    @Autowired
    SearchSimilarChunksUseCase vectorSearch;
    @Autowired
    SearchCandidatesByNameUseCase searchByName;
    @Autowired
    ResumeAiProperties properties;

    private static final Map<String, List<String>> CACHE = new HashMap<>();

    static List<Object[]> cases() {
        return List.of(
                new Object[] {"eletricista certificado NR-10 para manutenção de painéis elétricos", "Anderson Correia"},
                new Object[] {"enfermeira com atuação em UTI adulto e pronto-socorro", "Juliana Matos"},
                new Object[] {"gerente de projetos certificado PMP com gestão de portfólio", "Thiago Almeida"},
                new Object[] {"design system de aplicativo usado por dezenas de designers", "Bianca Costa"},
                new Object[] {"desenvolvedor Android com Kotlin e Jetpack Compose", "Felipe Nogueira"},
                new Object[] {"automação de testes end-to-end com Cypress", "Renata Souza"},
                new Object[] {"planejamento tributário, SPED e contabilidade fiscal", "Ricardo Teixeira"},
                new Object[] {"segurança de aplicações com certificações OSCP e CISSP", "Amanda Rocha"},
                new Object[] {"motorista com CNH categoria D e transporte executivo", "José Carlos Martins"},
                new Object[] {"staff engineer de Big Tech com sistemas distribuídos de altíssima escala", "Gustavo Pinheiro"},
                new Object[] {"professora alfabetizadora do ensino fundamental", "Márcia Oliveira"},
                new Object[] {"dashboards em Power BI e pipelines de ETL", "Patrícia Lima"},
                new Object[] {"recrutamento e seleção com folha de pagamento e benefícios", "Fernanda Castro"},
                new Object[] {"vigilante com monitoramento de CFTV em shopping center", "Marcos Vieira"},
                new Object[] {"frontend React com foco em Core Web Vitals e performance", "Diego Santana"},
                new Object[] {"modelo de detecção de fraude em tempo real com machine learning", "Larissa Moura"},
                new Object[] {"arquiteta de soluções cloud certificada em AWS, Azure e GCP", "Camila Azevedo"},
                new Object[] {"secretária executiva com agenda de diretoria e viagens corporativas", "Marisa Ferreira"},
                new Object[] {"auxiliar de cozinha com boas práticas de higiene em restaurante", "Maria Aparecida Silva"},
                new Object[] {"desenvolvedor full stack júnior em início de carreira", "Vitor Lopes"});
    }

    private int kProduction() {
        return properties.agent().candidatesPerSearch();
    }

    private int kMeasured() {
        return kProduction() + 7;
    }

    private List<String> ranked(String question) {
        return CACHE.computeIfAbsent(question, q -> {
            List<String> seen = new ArrayList<>();
            for (ResumeSnippet row : vectorSearch.execute(q, kMeasured() * OVERFETCH)) {
                if (row.candidateName() != null && !seen.contains(row.candidateName())) {
                    seen.add(row.candidateName());
                }
            }
            return seen.subList(0, Math.min(kMeasured(), seen.size()));
        });
    }

    private Integer rankOf(String question, String expected) {
        int index = ranked(question).indexOf(expected);
        return index < 0 ? null : index + 1;
    }

    @ParameterizedTest
    @MethodSource("cases")
    void expectedCandidateInProductionTopK(String question, String expected) {
        Integer rank = rankOf(question, expected);
        assertThat(rank != null && rank <= kProduction())
                .as("%s não apareceu no top-%d de %s. Top-%d: %s", expected, kProduction(), question, kMeasured(),
                        ranked(question))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rafael Mendes", "Márcia Oliveira", "Bruno Carvalho", "Amanda Rocha", "Vitor Lopes",
            "Marisa Ferreira"})
    void searchByProperNameReturnsTheResume(String name) {
        List<CandidateWithResume> found = searchByName.execute(name);
        assertThat(found).extracting(CandidateWithResume::name).containsExactly(name);
        assertThat(found.getFirst().chunks()).as("%s veio sem conteúdo de currículo", name).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"bruno carvalho,Bruno Carvalho", "marcia,Márcia Oliveira", "MENDES,Rafael Mendes",
            "carvalho bruno,Bruno Carvalho"})
    void searchByNameToleratesTyping(String term, String expected) {
        assertThat(searchByName.execute(term)).extracting(CandidateWithResume::name).containsExactly(expected);
    }

    @Test
    void nonexistentNameIsNotInvented() {
        assertThat(searchByName.execute("Fulano Inexistente da Silva")).isEmpty();
    }

    @Test
    void aggregateRecall() {
        List<Object[]> cases = cases();
        int hits = 0;
        StringBuilder table = new StringBuilder("\nRecall sobre " + cases.size() + " perguntas:\n");
        for (Object[] c : cases) {
            Integer rank = rankOf((String) c[0], (String) c[1]);
            boolean hit = rank != null && rank <= kProduction();
            hits += hit ? 1 : 0;
            table.append(String.format("  %s posição=%2s  %s%n", hit ? "ok " : "FORA", rank == null ? "-" : rank, c[1]));
        }
        double recall = (double) hits / cases.size();
        System.out.println(table.append(String.format("  recall@%d: %.0f%%", kProduction(), recall * 100)));
        assertThat(recall).as("recall@%d caiu abaixo do piso de %.0f%%", kProduction(), MIN_RECALL * 100)
                .isGreaterThanOrEqualTo(MIN_RECALL);
    }
}
