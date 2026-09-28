package dev.resumeplatform.resumeai.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

public final class SystemPrompt {
    private static final Pattern VARIABLE =
            Pattern.compile("\\$(?:(\\$)|([_a-z][_a-z0-9]*)|\\{([_a-z][_a-z0-9]*)})", Pattern.CASE_INSENSITIVE);
    private static final String INDENT = "    ";

    private SystemPrompt() {
    }

    public static String render(String filename, Map<String, Object> variables) {
        String text;
        try {
            text = new ClassPathResource("prompts/" + filename).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "$";
            } else {
                String name = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                if (!variables.containsKey(name)) {
                    throw new IllegalArgumentException("Variável sem valor no prompt " + filename + ": $" + name);
                }
                replacement = String.valueOf(variables.get(name));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String indent(String text) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(start, end);
            if (!line.isBlank()) {
                out.append(INDENT);
            }
            out.append(line);
            start = end;
        }
        return out.toString();
    }
}
