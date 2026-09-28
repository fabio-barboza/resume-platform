package dev.resumeplatform.resumeai.core.settings;

public record AgentSettings(int maxToolCallsPerQuestion, int candidatesPerSearch, String apiUrl) {
}
