package dev.resumeplatform.resumeai.service;

public record IngestionOutcome(
        long documentId,
        String filename,
        String status,
        long chunkCount,
        long candidateId,
        boolean duplicate) {
}
