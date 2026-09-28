package dev.resumeplatform.resumeai.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.db.CandidateChunkRow;
import dev.resumeplatform.resumeai.db.InventoryRow;
import dev.resumeplatform.resumeai.db.SimilarChunkRow;
import dev.resumeplatform.resumeai.service.CandidateService;
import dev.resumeplatform.resumeai.service.CandidateWithResume;
import dev.resumeplatform.resumeai.service.DocumentService;
import dev.resumeplatform.resumeai.service.IngestionService;
import dev.resumeplatform.resumeai.service.VectorSearchService;

@Component
public class ResumeTools {
    static final String NO_NAME = "(nome não identificado no currículo)";

    static final int OVERFETCH = 6;

    private final VectorSearchService vectorSearch;
    private final CandidateService candidateService;
    private final DocumentService documentService;
    private final ResumeAiProperties properties;

    public ResumeTools(VectorSearchService vectorSearch, CandidateService candidateService,
            DocumentService documentService, ResumeAiProperties properties) {
        this.vectorSearch = vectorSearch;
        this.candidateService = candidateService;
        this.documentService = documentService;
        this.properties = properties;
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
        int perSearch = properties.agent().candidatesPerSearch();
        List<SimilarChunkRow> best = bestPerCandidate(vectorSearch.similaritySearch(question, perSearch * OVERFETCH),
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
        List<CandidateWithResume> found = candidateService.searchByName(name);
        if (found.isEmpty()) {
            return "Nenhum candidato com nome parecido com " + IngestionService.pyRepr(name) + ". "
                    + "Este é o cadastro completo: se não está aqui, não está na base.";
        }

        List<String> blocks = new ArrayList<>();
        for (CandidateWithResume candidate : found) {
            String text = candidate.chunks().stream().map(CandidateChunkRow::content).collect(Collectors.joining("\n"));
            String filenames = candidate.chunks().stream()
                    .map(c -> basename(c.filename()))
                    .collect(Collectors.toCollection(java.util.TreeSet::new))
                    .stream().collect(Collectors.joining(", "));
            blocks.add("Candidato: " + formatIdentity(candidate.name(), candidate.email(), candidate.phone())
                    + " (candidato #" + candidate.id() + ")\n"
                    + "Currículo: " + (filenames.isEmpty() ? "sem arquivo" : filenames) + "\n"
                    + "Link para baixar o PDF: " + properties.api().url() + "/candidates/" + candidate.id() + "/resume\n"
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
        int total = documentService.listInventory().size();
        Map<String, Long> counts = candidateService.countBySkill(requested);
        if (counts.isEmpty()) {
            return "Total de candidatos na base: " + total + "\nNenhum termo informado.";
        }

        String lines = counts.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        String body = "Total de candidatos na base: " + total + "\n" + lines;

        List<String> ignored = CandidateService.normalizeSkillTerms(requested).stream()
                .filter(t -> !counts.containsKey(t))
                .toList();
        if (!ignored.isEmpty()) {
            body += "\nTermos ignorados (limite de " + CandidateService.MAX_SKILL_TERMS + " por chamada): "
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
        List<InventoryRow> records = documentService.listInventory();
        if (records.isEmpty()) {
            return "Total de currículos na base: 0\nA base está vazia.";
        }

        List<String> lines = new ArrayList<>();
        for (InventoryRow r : records) {
            String pending = IngestionService.STATUS_PENDING_REVIEW.equals(r.status())
                    ? "  [revisão pendente: nenhum email identificado no currículo]"
                    : "";

            lines.add("- documento #" + r.id() + " (candidato #" + r.candidateId() + "): "
                    + formatIdentity(r.name(), r.email(), r.phone())
                    + " (arquivo " + basename(r.filename()) + ")" + pending);
        }
        return "Total de currículos na base: " + records.size() + "\n" + String.join("\n", lines);
    }

    static List<SimilarChunkRow> bestPerCandidate(List<SimilarChunkRow> rows, int limit) {
        List<SimilarChunkRow> best = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        for (SimilarChunkRow row : rows) {
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

    private static String formatSnippet(SimilarChunkRow row) {
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
