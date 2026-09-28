package dev.resumeplatform.resumeai.core.domain.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.resumeplatform.resumeai.core.domain.chat.ChatMessage;
import dev.resumeplatform.resumeai.core.domain.text.PythonJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class GroundingGuardrail {
    private static final Logger log = LoggerFactory.getLogger(GroundingGuardrail.class);

    private static final Pattern CHART_FENCE = Pattern.compile("^\\s*```chart\\b", Pattern.MULTILINE);
    private static final Pattern RESUME_LINK = Pattern.compile("/candidates/\\d+/resume");

    private static final Pattern LINK_LABEL = Pattern.compile("[Ll]ink para (?:baixar|acessar|visualizar).{0,20}?:(.*)");

    private static final Pattern CHART_BLOCK =
            Pattern.compile("[ \\t]*```chart[ \\t]*\\n(.*?)\\n[ \\t]*```[ \\t]*\\n?", Pattern.DOTALL);

    private static final int MIN_CHART_CATEGORIES = 2;

    static final String RETRY_INSTRUCTION = "Correção automática do sistema, não do usuário: você respondeu a pergunta "
            + "acima sem chamar nenhuma ferramenta, e a resposta foi descartada antes de "
            + "chegar ao usuário. Nada sobre a base de currículos pode sair de memória — "
            + "nome de candidato, link de PDF e número de gráfico só existem se vierem de "
            + "uma tool call deste turno. Chame agora a ferramenta que responde à "
            + "pergunta e responda apenas com o que ela devolver.";

    static final String FAKE_LINK_INSTRUCTION = "Correção automática do sistema, não do usuário: você anunciou um link de "
            + "PDF que não é um link — nome de arquivo não abre nada, e a resposta foi "
            + "descartada antes de chegar ao usuário. O único link válido é o campo "
            + "'Link para baixar o PDF' que `find_candidate_by_name` devolve, no formato "
            + "/candidates/<candidate_id>/resume, copiado como está. Chame "
            + "`find_candidate_by_name` e responda com o link de lá, ou não mencione "
            + "link nenhum.";

    public static final String BLOCK_MESSAGE = "Não consegui responder isso com dados da base. Eu ia responder de memória, "
            + "e resposta sobre candidato que não sai de uma busca não vale nada — então "
            + "preferi não responder.\n\n"
            + "Tente de novo, ou diga qual recorte quer (tecnologia, senioridade, tempo "
            + "de experiência, nome) que eu monto a busca.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public sealed interface Verdict {
        record Pass() implements Verdict {
        }

        record Repair(String text) implements Verdict {
        }

        record Retry(String claim, ChatMessage.User correction) implements Verdict {
        }

        record GiveUp(String claim, String message) implements Verdict {
        }
    }

    private GroundingGuardrail() {
    }

    public static Verdict review(List<ChatMessage> messages) {
        if (messages.isEmpty() || !(messages.getLast() instanceof ChatMessage.Assistant last) || last.hasToolCalls()) {
            return new Verdict.Pass();
        }
        String text = last.text();

        String claim;
        String instruction;
        Optional<String> baseClaim;
        if (announcesFakePdfLink(text)) {
            claim = "link de PDF inválido";
            instruction = FAKE_LINK_INSTRUCTION;
        } else if (toolCallsThisTurn(messages) == 0 && (baseClaim = claimsAboutBase(text)).isPresent()) {
            claim = baseClaim.get();
            instruction = RETRY_INSTRUCTION;
        } else {
            String repaired = stripDegenerateCharts(text);
            if (!repaired.equals(text)) {
                log.info("Gráfico com menos de duas categorias removido da resposta.");
                return new Verdict.Repair(repaired);
            }
            return new Verdict.Pass();
        }

        boolean retried = alreadyRetried(messages);
        log.info("Resposta barrada pelo guardrail de grounding (sinal={}, segunda tentativa={}).", claim, retried);
        if (!retried) {
            return new Verdict.Retry(claim, correction(instruction));
        }
        return new Verdict.GiveUp(claim, BLOCK_MESSAGE);
    }

    public static ChatMessage.User correction(String instruction) {
        return new ChatMessage.User(instruction, true);
    }

    public static boolean isRetryCorrection(ChatMessage message) {
        return message instanceof ChatMessage.User user && user.groundingRetry();
    }

    static boolean announcesFakePdfLink(String text) {
        Matcher matcher = LINK_LABEL.matcher(text);
        while (matcher.find()) {
            if (!RESUME_LINK.matcher(matcher.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    static Optional<String> claimsAboutBase(String text) {
        if (CHART_FENCE.matcher(text).find()) {
            return Optional.of("gráfico");
        }
        if (RESUME_LINK.matcher(text).find()) {
            return Optional.of("link de currículo");
        }
        List<String> people = PersonMentions.find(text);
        return people.isEmpty() ? Optional.empty() : Optional.of("nome de pessoa (" + people.getFirst() + ")");
    }

    static int toolCallsThisTurn(List<ChatMessage> messages) {
        int calls = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ChatMessage.User) {
                break;
            }
            if (message instanceof ChatMessage.Assistant assistant) {
                calls += assistant.toolCalls().size();
            }
        }
        return calls;
    }

    static boolean alreadyRetried(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ChatMessage.User user) {
                return isRetryCorrection(user);
            }
        }
        return false;
    }

    public static String stripDegenerateCharts(String text) {
        Matcher matcher = CHART_BLOCK.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            String replacement = repairChart(matcher.group(0), matcher.group(1));
            changed |= !replacement.equals(matcher.group(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return changed ? out.toString().stripTrailing() : text;
    }

    private static String repairChart(String block, String json) {
        JsonNode chart;
        try {
            chart = JSON.readTree(json);
        } catch (RuntimeException ex) {
            return block;
        }
        if (!(chart instanceof ObjectNode object) || !(object.get("data") instanceof ArrayNode data)) {
            return block;
        }
        List<JsonNode> useful = new ArrayList<>();
        for (JsonNode item : data) {
            if (item.isObject() && hasValue(item.get("value"))) {
                useful.add(item);
            }
        }
        if (useful.size() < MIN_CHART_CATEGORIES) {
            return "";
        }
        if (useful.size() == data.size()) {
            return block;
        }
        ArrayNode pruned = JSON.createArrayNode();
        useful.forEach(pruned::add);
        object.set("data", pruned);
        log.info("Categorias sem valor removidas do gráfico.");
        return "```chart\n" + PythonJson.pretty(object) + "\n```\n";
    }

    private static boolean hasValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0.0;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return true;
    }
}
