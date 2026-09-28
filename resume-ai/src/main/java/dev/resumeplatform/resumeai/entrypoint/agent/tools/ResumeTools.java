package dev.resumeplatform.resumeai.entrypoint.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.domain.CandidateWithResume;
import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.core.domain.settings.AgentSettings;
import dev.resumeplatform.resumeai.core.domain.text.PyRepr;
import dev.resumeplatform.resumeai.core.usecase.candidate.CountCandidatesBySkillUseCase;
import dev.resumeplatform.resumeai.core.usecase.candidate.SearchCandidatesByNameUseCase;
import dev.resumeplatform.resumeai.core.usecase.resume.ListInventoryUseCase;
import dev.resumeplatform.resumeai.core.usecase.search.SearchSimilarChunksUseCase;

/**
 * As 4 ferramentas do agente. Para a aplicação o modelo é só mais um cliente, como a webui num controller:
 * cada tool traduz a chamada para um use case e formata o resultado. Nome, descrição e texto de saída são
 * contrato com o resume-agent.
 */
@Component
public class ResumeTools {
    static final String NO_NAME = "(nome não identificado no currículo)";

    static final int OVERFETCH = 6;

    private final SearchSimilarChunksUseCase searchSimilarChunks;
    private final SearchCandidatesByNameUseCase searchCandidatesByName;
    private final CountCandidatesBySkillUseCase countCandidatesBySkill;
    private final ListInventoryUseCase listInventory;
    private final AgentSettings settings;

    public ResumeTools(SearchSimilarChunksUseCase searchSimilarChunks,
            SearchCandidatesByNameUseCase searchCandidatesByName,
            CountCandidatesBySkillUseCase countCandidatesBySkill, ListInventoryUseCase listInventory,
            AgentSettings settings) {
        this.searchSimilarChunks = searchSimilarChunks;
        this.searchCandidatesByName = searchCandidatesByName;
        this.countCandidatesBySkill = countCandidatesBySkill;
        this.listInventory = listInventory;
        this.settings = settings;
    }

    @Tool(name = "find_in_resumes", resultConverter = RawStringResultConverter.class, description = """
            Busca semântica no conteúdo dos currículos.

            Use para encontrar candidatos por experiência, tecnologia, formação, cargo,
            empresa ou qualquer característica descrita no currículo. NÃO use para
            procurar alguém pelo nome: nome próprio não tem carga semântica e a busca
            devolve outros candidatos. Para nome, use `find_candidate_by_name`.

            Devolve o trecho mais parecido de cada candidato, um por candidato, dos
            mais próximos da consulta. Não é varredura da base inteira: ausência aqui
            não prova que o candidato não existe, e para isso serve `list_resumes`.
            O trecho é o que melhor casou com a pergunta, não o currículo inteiro —
            para o texto completo de alguém, use `find_candidate_by_name`.

            Args:
                question: o que procurar, em linguagem natural (ex.: "experiência com
                    mainframe e sistemas legados", "automação de testes com Cypress").""")
    public String findInResumes(@ToolParam(description = "o que procurar, em linguagem natural") String question) {
        int perSearch = settings.candidatesPerSearch();
        List<ResumeSnippet> best = bestPerCandidate(searchSimilarChunks.execute(question, perSearch * OVERFETCH),
                perSearch);
        return best.stream().map(ResumeTools::formatSnippet).collect(Collectors.joining("\n\n"));
    }

    @Tool(name = "find_candidate_by_name", resultConverter = RawStringResultConverter.class, description = """
            Busca um candidato pelo nome e devolve o currículo dele por inteiro.

            Use SEMPRE que o usuário citar alguém pelo nome. É busca textual no
            cadastro, não semântica: encontra "Márcia" digitando "marcia" e aceita as
            palavras em qualquer ordem. Não achar aqui é evidência de que o candidato
            não está na base — diferente de `find_in_resumes`, que só devolve os
            vizinhos mais próximos e pode não trazer a pessoa mesmo existindo.

            Args:
                name: nome ou parte do nome do candidato (ex.: "Bruno Carvalho",
                    "marcia", "mendes").""")
    public String findCandidateByName(@ToolParam(description = "nome ou parte do nome do candidato") String name) {
        List<CandidateWithResume> found = searchCandidatesByName.execute(name);
        if (found.isEmpty()) {
            return "Nenhum candidato com nome parecido com " + PyRepr.of(name) + ". "
                    + "Este é o cadastro completo: se não está aqui, não está na base.";
        }

        List<String> blocks = new ArrayList<>();
        for (CandidateWithResume candidate : found) {
            String text = candidate.chunks().stream().map(CandidateChunk::content).collect(Collectors.joining("\n"));
            String filenames = candidate.chunks().stream()
                    .map(c -> basename(c.filename()))
                    .collect(Collectors.toCollection(TreeSet::new))
                    .stream().collect(Collectors.joining(", "));
            blocks.add("Candidato: " + formatIdentity(candidate.name(), candidate.email(), candidate.phone())
                    + " (candidato #" + candidate.id() + ")\n"
                    + "Currículo: " + (filenames.isEmpty() ? "sem arquivo" : filenames) + "\n"
                    + "Link para baixar o PDF: " + settings.apiUrl() + "/candidates/" + candidate.id() + "/resume\n"
                    + "Conteudo: " + (text.isEmpty() ? "(currículo sem texto extraído)" : text));
        }
        return String.join("\n\n", blocks);
    }

    @Tool(name = "count_candidates_by_skill", resultConverter = RawStringResultConverter.class, description = """
            Conta quantos candidatos distintos citam cada termo no currículo.

            Use antes de qualquer resposta com número por tecnologia, e sempre que for
            montar gráfico ou comparar quantidade entre termos — é a única ferramenta
            que mede em vez de estimar pelos trechos que `find_in_resumes` traz.

            É busca LITERAL (substring, sem acento, sem caixa), não semântica: não
            agrupa sinônimo nem variação ("Postgres" e "PostgreSQL" contam
            separado) — cabe a você escolher os termos certos, inclusive variações,
            se quiser somá-las depois. Não serve para julgar profundidade ou tempo de
            experiência, só presença do termo no texto.

            PASSE TODOS OS TERMOS NUMA CHAMADA SÓ: a lista existe para gastar UMA
            chamada do orçamento de ferramentas por pergunta, não uma por tecnologia.
            O limite é de 10 termos por chamada; o excedente é ignorado e vem listado
            na resposta. Escolha os 10 que importam em vez de dividir em lotes.

            Args:
                skills: termos a contar, um por tecnologia/critério (ex.: ["Python",
                    "Java", "Cobol"]).""")
    public String countCandidatesBySkill(
            @ToolParam(description = "termos a contar, um por tecnologia/critério") List<String> skills) {
        List<String> requested = skills == null ? List.of() : skills;
        int total = listInventory.execute().size();
        Map<String, Long> counts = countCandidatesBySkill.execute(requested);
        if (counts.isEmpty()) {
            return "Total de candidatos na base: " + total + "\nNenhum termo informado.";
        }

        String lines = counts.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        String body = "Total de candidatos na base: " + total + "\n" + lines;

        List<String> ignored = CountCandidatesBySkillUseCase.normalizeSkillTerms(requested).stream()
                .filter(t -> !counts.containsKey(t))
                .toList();
        if (!ignored.isEmpty()) {
            body += "\nTermos ignorados (limite de " + CountCandidatesBySkillUseCase.MAX_SKILL_TERMS + " por chamada): "
                    + String.join(", ", ignored);
        }
        return body;
    }

    @Tool(name = "list_resumes", resultConverter = RawStringResultConverter.class, description = """
            Lista o inventário completo da base: todo candidato e seu currículo.

            Use ANTES de qualquer afirmação sobre o conjunto de candidatos — "não
            existe", "o único", "todos", "nenhum", "quantos". Não faz busca semântica:
            devolve o cadastro completo e exato, com nome, email e telefone de cada
            candidato. Serve também para responder sobre contato de alguém sem
            precisar buscar no conteúdo do currículo.

            Não traz o conteúdo dos currículos: para experiência, tecnologia, formação
            ou qualquer coisa descrita no texto, use `find_in_resumes`.""")
    public String listResumes() {
        List<InventoryEntry> records = listInventory.execute();
        if (records.isEmpty()) {
            return "Total de currículos na base: 0\nA base está vazia.";
        }

        List<String> lines = new ArrayList<>();
        for (InventoryEntry r : records) {
            String pending = r.status() == ResumeStatus.PENDING_REVIEW
                    ? "  [revisão pendente: nenhum email identificado no currículo]"
                    : "";

            lines.add("- documento #" + r.id() + " (candidato #" + r.candidateId() + "): "
                    + formatIdentity(r.name(), r.email(), r.phone())
                    + " (arquivo " + basename(r.filename()) + ")" + pending);
        }
        return "Total de currículos na base: " + records.size() + "\n" + String.join("\n", lines);
    }

    static List<ResumeSnippet> bestPerCandidate(List<ResumeSnippet> rows, int limit) {
        List<ResumeSnippet> best = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        for (ResumeSnippet row : rows) {
            Object key = row.candidateId() != null ? row.candidateId() : new Object();
            if (!seen.add(key)) {
                continue;
            }
            best.add(row);
            if (best.size() == limit) {
                break;
            }
        }
        return best;
    }

    static String formatIdentity(String name, String email, String phone) {
        String contact = Stream.of(email, phone).filter(Objects::nonNull).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" | "));
        return (name != null && !name.isEmpty() ? name : NO_NAME) + " — "
                + (contact.isEmpty() ? "sem contato registrado" : contact);
    }

    private static String formatSnippet(ResumeSnippet row) {
        return "Candidato: " + formatIdentity(row.candidateName(), row.candidateEmail(), null)
                + " (candidato #" + row.candidateId() + ")\n"
                + "Currículo: documento #" + row.documentId()
                + " (arquivo " + basename(row.filename() == null ? "desconhecido" : row.filename())
                + ", página " + (row.page() + 1) + ")\n"
                + "Conteudo: " + row.content();
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
