package dev.resumeplatform.resumeai.core.domain;

public record CandidateChunk(String content, int page, int chunkIndex, long documentId, String filename) {
}
