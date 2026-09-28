package dev.resumeplatform.resumeai.core.domain.chat;

import java.util.List;

public sealed interface ChatMessage {
    String text();

    record User(String text, boolean groundingRetry) implements ChatMessage {
        public User(String text) {
            this(text, false);
        }
    }

    record Assistant(String text, List<ToolCall> toolCalls) implements ChatMessage {
        public Assistant {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public Assistant(String text) {
            this(text, List.of());
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    record ToolResults(List<ToolResult> results) implements ChatMessage {
        public ToolResults {
            results = List.copyOf(results);
        }

        @Override
        public String text() {
            return "";
        }
    }
}
