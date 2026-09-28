package dev.resumeplatform.resumeai.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonMentionsTest {
    @Test
    void conjunctionDoesNotGlueTwoNames() {
        assertThat(PersonMentions.find("Os três (Larissa, Carlos e Patrícia) seguem sendo as melhores."))
                .doesNotContain("Carlos e Patrícia");
    }

    @Test
    void twoNamesJoinedByConjunctionAreBothFound() {
        var mentions = PersonMentions.find("Encontrei Amanda Rocha e Bruno Carvalho na base.");
        assertThat(mentions).anyMatch(m -> m.contains("Amanda Rocha"));
        assertThat(mentions).anyMatch(m -> m.contains("Bruno Carvalho"));
    }

    @Test
    void productAndProtocolNamesAreNotPeople() {
        assertThat(PersonMentions.find("Tem experiência com Model Context Protocol, Development "
                + "Orchestrator, Resume Platform e Aprendizado de Máquina.")).isEmpty();
    }

    @Test
    void realNameWithSurnameParticleIsFound() {
        assertThat(PersonMentions.find("**Fabio Barboza de Oliveira** (barboza@example.com) trabalha com RAG."))
                .containsExactly("Fabio Barboza de Oliveira");
    }

    @Test
    void frameworkNamesAreNotPeople() {
        assertThat(PersonMentions.find("Domina Python e Java (Spring Boot) e Google Cloud Platform.")).isEmpty();
    }

    @Test
    void sectionHeadingsAndConceptsAreNotPeople() {
        assertThat(PersonMentions.find("Vivência em Agentes Autônomos e Modelos Preditivos. "
                + "Recomendação Final: o perfil mais alinhado é o primeiro.")).isEmpty();
    }

    @Test
    void englishJobTitlesAreNotPeople() {
        assertThat(PersonMentions.find("Atuou como Staff Engineer e Product Owner, com perfil Full Stack "
                + "e passagem por Site Reliability.")).isEmpty();
    }

    @Test
    void employerAfterPrepositionIsNotAPerson() {
        assertThat(PersonMentions.find("Atuou na Magazine Luiza e no Banco Inter antes da Nubank.")).isEmpty();
    }

    @Test
    void employerListAfterComoIsNotAPerson() {
        assertThat(PersonMentions.find("Sua atuação em empresas como Magazine Luiza e Banco Inter "
                + "demonstra capacidade de transformar dados em insights.")).isEmpty();
    }

    @Test
    void personAfterEmployerListIsStillFound() {
        assertThat(PersonMentions.find("Atuou na Magazine Luiza e no Banco Inter com Larissa Moura."))
                .containsExactly("Larissa Moura");
    }

    @Test
    void nameAfterDeStillCountsAsPerson() {
        assertThat(PersonMentions.find("O currículo do Gustavo Pinheiro e o da Ana Martins."))
                .containsExactly("Gustavo Pinheiro", "Ana Martins");
    }

    @Test
    void hyphenatedCompoundIsNotAPerson() {
        assertThat(PersonMentions.find("Usa Retrieval-Augmented Generation em produção.")).isEmpty();
    }

    @Test
    void toolNamesAreNotPeople() {
        assertThat(PersonMentions.find("Testado em Claude Code, Cursor e Copilot.")).isEmpty();
    }

    @Test
    void employerAtSentenceStartIsNotAPerson() {
        assertThat(PersonMentions.find("Na Netflix, arquitetou serviços de recomendação. No Nubank, liderou dados."))
                .isEmpty();
    }
}
