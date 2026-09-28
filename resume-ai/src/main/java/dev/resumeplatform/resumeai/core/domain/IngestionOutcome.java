package dev.resumeplatform.resumeai.core.domain;

public record IngestionOutcome(
        long documentId,
        String filename,
        ResumeStatus status,
        long chunkCount,
        long candidateId,
        boolean duplicate) {
    public static IngestionOutcome duplicateOf(Resume resume) {
        return new IngestionOutcome(resume.id(), resume.filename(), resume.status(), resume.chunkCount(),
                resume.candidate().id(), true);
    }
}
