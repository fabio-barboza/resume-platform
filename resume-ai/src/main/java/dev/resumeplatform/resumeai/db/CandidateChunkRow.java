package dev.resumeplatform.resumeai.db;

public record CandidateChunkRow(String content, int page, int chunkIndex, Long documentId, String filename) {
}
