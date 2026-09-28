package dev.resumeplatform.resumeai.entrypoint.controller.sse;

import java.util.Map;

public record ChatEvent(String type, Map<String, Object> data) {
    public static ChatEvent start(String sessionId) {
        return new ChatEvent("start", Map.of("session_id", sessionId));
    }

    public static ChatEvent tool(String name, String status) {
        return new ChatEvent("tool", Map.of("name", name, "status", status));
    }

    public static ChatEvent token(String text) {
        return new ChatEvent("token", Map.of("text", text));
    }

    public static ChatEvent reset() {
        return new ChatEvent("reset", Map.of());
    }

    public static ChatEvent done(String content) {
        return new ChatEvent("done", Map.of("content", content));
    }

    public static ChatEvent error(String detail) {
        return new ChatEvent("error", Map.of("detail", detail));
    }
}
