package dev.resumeplatform.resumeai.api.dto;

import java.time.OffsetDateTime;

import dev.resumeplatform.resumeai.db.ResumeRow;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResumeSummary(
        long documentId,
        String filename,
        String fileHash,
        int pages,
        String status,
        OffsetDateTime ingestedAt,
        long chunkCount,
        CandidateSummary candidate) {
    public static ResumeSummary from(ResumeRow row) {
        return new ResumeSummary(row.id(), row.filename(), row.fileHash(), row.pages(), row.status(), row.ingestedAt(),
                row.chunkCount(), new CandidateSummary(row.candidateId(), row.candidateName(), row.candidateEmail(),
                        row.candidatePhone(), row.candidateCreatedAt()));
    }
}
