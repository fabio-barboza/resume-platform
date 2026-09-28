package dev.resumeplatform.resumeai.infra.dto;

public record CandidateChunkDto(String content, int page, int chunkIndex, Long documentId, String filename) {
}
