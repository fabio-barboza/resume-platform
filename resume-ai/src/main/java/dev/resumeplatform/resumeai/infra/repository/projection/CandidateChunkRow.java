package dev.resumeplatform.resumeai.infra.repository.projection;

public record CandidateChunkRow(String content, int page, int chunkIndex, Long documentId, String filename) {
}
