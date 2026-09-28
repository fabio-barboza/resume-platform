package dev.resumeplatform.resumeai.core.support.text;

import java.util.Map;

import tools.jackson.databind.JsonNode;

public final class PythonJson {
    private PythonJson() {
    }

    public static String pretty(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out, 0);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, int depth) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                indent(out, depth + 1);
                out.append(quote(entry.getKey())).append(": ");
                write(entry.getValue(), out, depth + 1);
                out.append(++i < node.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                indent(out, depth + 1);
                write(node.get(i), out, depth + 1);
                out.append(i + 1 < node.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else if (node.isString()) {
            out.append(quote(node.stringValue()));
        } else {
            out.append(node.toString());
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
