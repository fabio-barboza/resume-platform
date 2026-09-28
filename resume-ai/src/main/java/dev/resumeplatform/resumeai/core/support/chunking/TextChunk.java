package dev.resumeplatform.resumeai.core.support.chunking;

public record TextChunk(int page, int chunkIndex, String content) {
    public String idFor(long documentId) {
        return documentId + "-p" + page + "-c" + chunkIndex;
    }
}
