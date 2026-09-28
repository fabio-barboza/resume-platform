package dev.resumeplatform.resumeai.core.domain;

public record ResumeSnippet(
        String content,
        int page,
        int chunkIndex,
        long documentId,
        String filename,
        Long candidateId,
        String candidateName,
        String candidateEmail,
        double distance) {
    public double score() {
        return 1.0 - distance;
    }
}
