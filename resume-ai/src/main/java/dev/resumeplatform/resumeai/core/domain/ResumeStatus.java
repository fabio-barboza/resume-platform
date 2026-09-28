package dev.resumeplatform.resumeai.core.domain;

import java.util.Arrays;

public enum ResumeStatus {
    INGESTED("ingested"),
    PENDING_REVIEW("pending_review");

    private final String value;

    ResumeStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ResumeStatus fromValue(String value) {
        return Arrays.stream(values()).filter(s -> s.value.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Status de documento desconhecido: " + value));
    }
}
