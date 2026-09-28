package dev.resumeplatform.resumeai.core.domain;

import java.util.List;

import dev.resumeplatform.resumeai.core.support.chunking.TextChunk;

public record AnalyzedResume(
        List<String> pages,
        List<TextChunk> chunks,
        List<float[]> embeddings,
        CandidateIdentity identity) {
    public ResumeStatus status() {
        return identity.status();
    }
}
