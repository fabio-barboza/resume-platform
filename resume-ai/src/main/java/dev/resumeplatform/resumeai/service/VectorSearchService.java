package dev.resumeplatform.resumeai.service;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.db.ChunkRepository;
import dev.resumeplatform.resumeai.db.SimilarChunkRow;

@Service
public class VectorSearchService {
    private final EmbeddingModel embeddingModel;
    private final ChunkRepository chunks;

    public VectorSearchService(EmbeddingModel embeddingModel, ChunkRepository chunks) {
        this.embeddingModel = embeddingModel;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public List<SimilarChunkRow> similaritySearch(String question, int k) {
        float[] queryEmbedding = embeddingModel.embed(question);
        return chunks.findNearest(queryEmbedding, Limit.of(k));
    }
}
