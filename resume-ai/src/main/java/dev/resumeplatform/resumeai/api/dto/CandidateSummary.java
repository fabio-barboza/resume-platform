package dev.resumeplatform.resumeai.api.dto;

import java.time.OffsetDateTime;

import dev.resumeplatform.resumeai.db.Candidate;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidateSummary(
        long id,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String email,
        @Schema(nullable = true) String phone,
        OffsetDateTime createdAt) {
    public static CandidateSummary from(Candidate candidate) {
        return new CandidateSummary(candidate.getId(), candidate.getName(), candidate.getEmail(), candidate.getPhone(),
                candidate.getCreatedAt());
    }
}
