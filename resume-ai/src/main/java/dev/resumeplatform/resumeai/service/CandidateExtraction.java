package dev.resumeplatform.resumeai.service;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CandidateExtraction(
        @JsonPropertyDescription("Nome completo do candidato, ou null.") String name,
        @JsonPropertyDescription("Email de contato do candidato, ou null.") String email,
        @JsonPropertyDescription("Telefone de contato do candidato, ou null.") String phone) {
    public static CandidateExtraction empty() {
        return new CandidateExtraction(null, null, null);
    }

    public boolean hasIdentity() {
        return name != null || email != null || phone != null;
    }
}
