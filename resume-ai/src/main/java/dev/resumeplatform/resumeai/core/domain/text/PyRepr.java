package dev.resumeplatform.resumeai.core.domain.text;

public final class PyRepr {
    private PyRepr() {
    }

    public static String of(String value) {
        String quote = value.contains("'") && !value.contains("\"") ? "\"" : "'";
        String escaped = value.replace("\\", "\\\\");
        if (quote.equals("'")) {
            escaped = escaped.replace("'", "\\'");
        }
        return quote + escaped + quote;
    }
}
