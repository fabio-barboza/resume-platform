package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;
import java.util.Map;

import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.support.chunking.TextChunk;

public interface ChunkGateway {
    long saveAll(long documentId, List<TextChunk> chunks, List<float[]> embeddings);

    void deleteByDocument(long documentId);

    List<CandidateChunk> findByCandidate(long candidateId);

    List<ResumeSnippet> findNearest(float[] embedding, int limit);

    Map<String, Long> countCandidatesByTerms(List<String> terms);
}
