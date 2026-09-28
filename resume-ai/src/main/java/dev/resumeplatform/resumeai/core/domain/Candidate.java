package dev.resumeplatform.resumeai.core.domain;

import java.time.OffsetDateTime;

public record Candidate(long id, String name, String email, String phone, OffsetDateTime createdAt) {
}
