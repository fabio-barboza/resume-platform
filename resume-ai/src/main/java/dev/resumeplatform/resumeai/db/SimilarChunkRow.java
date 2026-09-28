package dev.resumeplatform.resumeai.db;

public record SimilarChunkRow(
        String content,
        int page,
        int chunkIndex,
        Long documentId,
        String filename,
        Long candidateId,
        String candidateName,
        String candidateEmail,
        double distance) {
    public double score() {
        return 1.0 - distance;
    }
}
