package dev.resumeplatform.resumeai.entrypoint.controller.response;

import java.time.OffsetDateTime;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResumeResponse(
        long documentId,
        String filename,
        String fileHash,
        int pages,
        String status,
        OffsetDateTime ingestedAt,
        long chunkCount,
        CandidateResponse candidate) {
}
