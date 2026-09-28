package dev.resumeplatform.resumeai.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ExtractionService {
    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern PHONE =
            Pattern.compile("(?:\\+55[\\s.-]?)?(?:\\(?\\d{2}\\)?[\\s.-]?)?\\d{4,5}[\\s.-]?\\d{4}");
    private static final Set<String> NULL_LITERALS = Set.of("null", "none", "n/a", "-");

    static final String PROMPT = """
            Extraia os dados de contato do candidato a partir do trecho de \
            currículo abaixo.

            Regras:
            - `name`: o nome completo do candidato dono do currículo. Não confunda com \
            nome de empresa, faculdade, curso, cliente ou referência profissional.
            - `email`: o email de contato do próprio candidato.
            - `phone`: o telefone de contato do próprio candidato.
            - Se um dado não estiver no texto, devolva null. Não invente e não deduza.

            Currículo:
            ---
            %s
            ---""";

    private final ChatClient chatClient;

    public ExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public CandidateExtraction extract(String text) {
        if (text == null || text.isBlank()) {
            return CandidateExtraction.empty();
        }

        CandidateExtraction extracted = CandidateExtraction.empty();
        try {
            CandidateExtraction result = chatClient.prompt().user(PROMPT.formatted(text)).call()
                    .entity(CandidateExtraction.class);
            if (result != null) {
                extracted = result;
            }
        } catch (RuntimeException ex) {
            log.warn("Extração via LLM falhou; caindo no fallback por regex.");
        }

        String name = normalize(extracted.name());
        String email = orElse(normalize(extracted.email()), firstMatch(EMAIL, text));
        String phone = orElse(normalize(extracted.phone()), firstMatch(PHONE, text));
        return new CandidateExtraction(name, email, phone);
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || NULL_LITERALS.contains(cleaned.toLowerCase())) {
            return null;
        }
        return cleaned;
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
