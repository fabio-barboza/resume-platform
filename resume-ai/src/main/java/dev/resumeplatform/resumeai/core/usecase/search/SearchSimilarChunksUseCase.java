package dev.resumeplatform.resumeai.core.usecase.search;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;
import dev.resumeplatform.resumeai.core.gateway.EmbeddingGateway;

@Service
public class SearchSimilarChunksUseCase {
    private final EmbeddingGateway embeddings;
    private final ChunkGateway chunks;

    public SearchSimilarChunksUseCase(EmbeddingGateway embeddings, ChunkGateway chunks) {
        this.embeddings = embeddings;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public List<ResumeSnippet> execute(String question, int k) {
        return chunks.findNearest(embeddings.embed(question), k);
    }
}
