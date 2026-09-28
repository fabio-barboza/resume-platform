package dev.resumeplatform.resumeai.entrypoint.response;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidateResponse(
        long id,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String email,
        @Schema(nullable = true) String phone,
        OffsetDateTime createdAt) {
}
