package dev.resumeplatform.resumeai.core.usecase.chat;

final class HallucinatedAnswers {
    static final String HALLUCINATED_ANSWER = """
            Para identificar o melhor candidato para a vaga de \
            Engenheiro de IA Aplicada, realizei uma busca semântica.

            1. **Lucas Mendes** — Engenharia de Machine Learning e MLOps.
            2. **Fernanda Lima** — Cientista de Dados Sênior com foco em Deep Learning.
            3. **Rafael Costa** — Visão Computacional e Análise Preditiva.
            """;

    static final String RATIONALIZED_ANSWER = "Fabio Barboza de Oliveira foi sim considerado, mas não "
            + "apareceu no top 3 por uma questão de peso semântico na busca automática.";

    private HallucinatedAnswers() {
    }
}
