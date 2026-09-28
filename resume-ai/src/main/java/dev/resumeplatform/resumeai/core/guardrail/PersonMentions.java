package dev.resumeplatform.resumeai.core.guardrail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.resumeplatform.resumeai.core.support.text.TextFolding;

public final class PersonMentions {
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    private static final String WORD = "[A-ZÁÀÂÃÉÊÍÓÔÕÚÇ][a-záàâãéêíóôõúç]+";
    private static final String LINK_WORD = "d[aeo]s?";
    private static final Pattern PROPER_NAME =
            Pattern.compile("\\b" + WORD + "(?:\\s+(?:" + LINK_WORD + "\\s+)?" + WORD + ")+\\b", FLAGS);

    private static final Pattern EMPLOYER_PREFIX = Pattern.compile(
            "\\b(?:[Nn][ao]s?|empresas?(?:\\s+como)?|clientes?(?:\\s+como)?"
                    + "|companhias?|bancos?|startups?)\\s+$",
            FLAGS);

    private static final Pattern ENUMERATION_GAP = Pattern.compile("^[\\s,]*(?:e|ou)?[\\s,]*$", FLAGS);

    private static final Pattern EMPLOYER_LEAD = Pattern.compile("^N[ao]s?\\s", FLAGS);

    static final Set<String> NOT_A_PERSON = Set.copyOf(Arrays.asList(
            "agente", "agentes", "analista", "analytics", "api", "aplicada",
            "aplicado", "aprendizado", "arquiteta", "arquiteto", "arquitetura",
            "artificial", "atua", "augmented", "autonoma", "autonomas", "autonomo",
            "autonomos", "backend", "base", "biblioteca", "big", "boot", "candidata",
            "candidatas", "candidato", "candidatos", "certificacao", "ciencia",
            "ciencias", "cientista", "cloud", "code", "competencias", "computacao",
            "computacional", "context", "contexto", "coordenador", "curriculo",
            "curriculos", "dados", "data", "deep", "desenvolvedor", "desenvolvedora",
            "developer", "development", "devops", "digital", "distribuidos",
            "doutorado", "engenharia", "engenheira", "engenheiro", "engineer",
            "engineering", "especializacao", "experiencia", "final", "formacao",
            "framework", "frameworks", "frontend", "full", "generation", "generativa",
            "generative", "gestao", "graduacao", "habilidades", "inteligencia", "java",
            "junior", "language", "lead", "learning", "lideranca", "linguagem", "link",
            "machine", "manager", "maquina", "mestrado", "model", "modelos", "natural",
            "neural", "orchestrator", "orquestracao", "orquestrador", "owner",
            "pipeline", "pipelines", "plataforma", "plataformas", "platform", "pleno",
            "pos", "possui", "preditiva", "preditivo", "preditivos", "principal",
            "processamento", "processing", "product", "profissional", "projeto",
            "projetos", "protocol", "protocolo", "python", "recomendacao",
            "recomendacoes", "redes", "reliability", "resume", "resumo", "retrieval",
            "science", "scientist", "senior", "sistemas", "site", "software",
            "solucoes", "solutions", "specialist", "spring", "stack", "staff",
            "swagger", "tech", "tecnologia", "tecnologias", "visao", "vision"));

    private PersonMentions() {
    }

    public static List<String> find(String text) {
        List<String> mentions = new ArrayList<>();
        Integer previousEmployerEnd = null;
        Matcher matcher = PROPER_NAME.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();

            if (start > 0 && text.charAt(start - 1) == '-') {
                continue;
            }
            String before = text.substring(Math.max(0, start - 24), start);
            boolean continuesList = previousEmployerEnd != null
                    && ENUMERATION_GAP.matcher(text.substring(previousEmployerEnd, start)).find();
            if (EMPLOYER_PREFIX.matcher(before).find() || EMPLOYER_LEAD.matcher(matcher.group()).find()
                    || continuesList) {
                previousEmployerEnd = matcher.end();
                continue;
            }
            previousEmployerEnd = null;
            String span = matcher.group();
            boolean technical = Arrays.stream(span.split("\\s+"))
                    .map(TextFolding::fold)
                    .anyMatch(NOT_A_PERSON::contains);
            if (!technical) {
                mentions.add(span);
            }
        }
        return mentions;
    }
}
