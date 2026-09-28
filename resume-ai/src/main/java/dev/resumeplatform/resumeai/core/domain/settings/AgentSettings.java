package dev.resumeplatform.resumeai.core.domain.settings;

public record AgentSettings(int maxToolCallsPerQuestion, int candidatesPerSearch, String apiUrl) {
}
