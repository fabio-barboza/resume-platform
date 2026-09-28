package dev.resumeplatform.resumeai.db;

import java.time.OffsetDateTime;

public record ResumeRow(
        Long id,
        Long candidateId,
        String filename,
        String fileHash,
        int pages,
        String status,
        OffsetDateTime ingestedAt,
        String candidateName,
        String candidateEmail,
        String candidatePhone,
        OffsetDateTime candidateCreatedAt,
        long chunkCount) {
}
