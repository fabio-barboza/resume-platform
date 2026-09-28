package dev.resumeplatform.resumeai.core.domain;

import java.time.OffsetDateTime;

public record Resume(
        long id,
        String filename,
        String fileHash,
        int pages,
        ResumeStatus status,
        OffsetDateTime ingestedAt,
        long chunkCount,
        Candidate candidate) {
}
