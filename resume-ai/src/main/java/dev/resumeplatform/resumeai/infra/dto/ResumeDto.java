package dev.resumeplatform.resumeai.infra.dto;

import java.time.OffsetDateTime;

public record ResumeDto(
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
