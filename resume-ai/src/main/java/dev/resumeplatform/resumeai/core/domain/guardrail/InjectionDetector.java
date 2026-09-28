package dev.resumeplatform.resumeai.core.domain.guardrail;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.resumeplatform.resumeai.core.domain.text.TextFolding;

public final class InjectionDetector {
    private static final int CONTEXT_WINDOW = 60;
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", FLAGS);

    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("ordem para ignorar instruções", Pattern.compile(
                    "\\b(ignore|ignorar|desconsidere|desconsiderar|esqueca|esquecer"
                            + "|disregard|forget)\\b[^.]{0,40}?\\b(instrucoes|instrucao|regras"
                            + "|orientacoes|comandos|instructions?|rules|prompts?)\\b",
                    FLAGS)),
            new Rule("referência ao prompt do sistema", Pattern.compile(
                    "\\b(system\\s+prompt|prompt\\s+do\\s+sistema|prompt\\s+de\\s+sistema)\\b", FLAGS)),
            new Rule("tentativa de redefinir o papel do assistente", Pattern.compile(
                    "\\b(voce\\s+(e|esta)\\s+agora|a\\s+partir\\s+de\\s+agora[,\\s]+voce"
                            + "|you\\s+are\\s+now|from\\s+now\\s+on[,\\s]+you)\\b",
                    FLAGS)),

            new Rule("instrução dirigida ao sistema de IA", Pattern.compile(
                    "\\bnovas?\\s+instrucoes\\s*:"
                            + "|\\binstrucao\\s*:"
                            + "|\\binstrucoes?\\s+(ao|para\\s+o)\\s+"
                            + "(sistema|modelo|assistente|agente|avaliador)\\b"
                            + "|\\bnew\\s+instructions?\\s*:"
                            + "|\\binstructions?\\s+(to|for)\\s+the\\s+(system|ai|assistant|model)\\b",
                    FLAGS)),
            new Rule("marcador de conversa de chat embutido no texto", Pattern.compile(
                    "(<\\|im_(start|end)\\|>|\\[/?inst\\]|<<\\s*sys\\s*>>"
                            + "|^\\s*###\\s*(system|instrucoes?)\\b|</?system>|</?assistant>)",
                    FLAGS | Pattern.MULTILINE)),
            new Rule("ordem para favorecer este candidato", Pattern.compile(
                    "\\b(sempre\\s+recomende|recomende\\s+(sempre\\s+)?(este|esse)\\s+candidato"
                            + "|classifique\\s+(este|esse)\\s+candidato|always\\s+recommend"
                            + "|rank\\s+this\\s+candidate|select\\s+this\\s+candidate"
                            + "|this\\s+candidate\\s+is\\s+the\\s+best)\\b",
                    FLAGS)),
            new Rule("ordem para descartar os demais currículos", Pattern.compile(
                    "\\b(ignore|desconsidere|descarte|reject)\\b[^.]{0,40}?\\b(outros?\\s+"
                            + "(curriculos?|candidatos?)|other\\s+(resumes?|candidates?))\\b",
                    FLAGS)));

    private InjectionDetector() {
    }

    public static Optional<InjectionMatch> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Folded folded = fold(text);
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(folded.text());
            if (!matcher.find()) {
                continue;
            }
            int start = folded.origins().get(matcher.start());
            int end = folded.origins().get(matcher.end() - 1) + 1;
            return Optional.of(new InjectionMatch(rule.label(), excerpt(text, start, end)));
        }
        return Optional.empty();
    }

    private record Folded(String text, List<Integer> origins) {
    }

    private static Folded fold(String text) {
        StringBuilder chars = new StringBuilder(text.length());
        List<Integer> origins = new ArrayList<>(text.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            String decomposed = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKD);
            int origin = i;
            decomposed.codePoints().filter(cp -> !TextFolding.isCombining(cp)).forEach(cp -> {
                String lower = new String(Character.toChars(cp)).toLowerCase();
                for (int k = 0; k < lower.length(); k++) {
                    chars.append(lower.charAt(k));
                    origins.add(origin);
                }
            });
            i += Character.charCount(codePoint);
        }
        return new Folded(chars.toString(), origins);
    }

    private static String excerpt(String text, int start, int end) {
        String window = text.substring(Math.max(0, start - CONTEXT_WINDOW), Math.min(text.length(), end + CONTEXT_WINDOW));
        String normalized = WHITESPACE.matcher(window.strip()).replaceAll(" ");
        String prefix = start - CONTEXT_WINDOW > 0 ? "..." : "";
        String suffix = end + CONTEXT_WINDOW < text.length() ? "..." : "";
        return prefix + normalized + suffix;
    }
}
