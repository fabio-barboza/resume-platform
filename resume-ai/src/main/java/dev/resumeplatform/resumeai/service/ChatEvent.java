package dev.resumeplatform.resumeai.service;

import java.util.Map;

public record ChatEvent(String type, Map<String, Object> data) {
    static ChatEvent start(String sessionId) {
        return new ChatEvent("start", Map.of("session_id", sessionId));
    }

    static ChatEvent tool(String name, String status) {
        return new ChatEvent("tool", Map.of("name", name, "status", status));
    }

    static ChatEvent token(String text) {
        return new ChatEvent("token", Map.of("text", text));
    }

    static ChatEvent reset() {
        return new ChatEvent("reset", Map.of());
    }

    static ChatEvent done(String content) {
        return new ChatEvent("done", Map.of("content", content));
    }

    static ChatEvent error(String detail) {
        return new ChatEvent("error", Map.of("detail", detail));
    }
}
