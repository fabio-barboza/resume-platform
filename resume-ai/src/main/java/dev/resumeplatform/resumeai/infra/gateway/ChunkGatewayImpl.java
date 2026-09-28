package dev.resumeplatform.resumeai.infra.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.domain.chunking.TextChunk;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;
import dev.resumeplatform.resumeai.infra.repository.ChunkRepository;
import dev.resumeplatform.resumeai.infra.repository.DocumentRepository;
import dev.resumeplatform.resumeai.infra.repository.entity.ChunkEntity;
import dev.resumeplatform.resumeai.infra.repository.entity.DocumentEntity;
import dev.resumeplatform.resumeai.infra.repository.mapper.ResumeEntityMapper;

@Component
public class ChunkGatewayImpl implements ChunkGateway {
    private final ChunkRepository chunks;
    private final DocumentRepository documents;

    public ChunkGatewayImpl(ChunkRepository chunks, DocumentRepository documents) {
        this.chunks = chunks;
        this.documents = documents;
    }

    @Override
    public long saveAll(long documentId, List<TextChunk> pieces, List<float[]> embeddings) {
        DocumentEntity document = documents.getReferenceById(documentId);
        List<ChunkEntity> rows = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            TextChunk piece = pieces.get(i);
            rows.add(new ChunkEntity(piece.idFor(documentId), document, piece.page(), piece.chunkIndex(),
                    piece.content(), embeddings.get(i)));
        }
        chunks.saveAll(rows);
        chunks.flush();
        return rows.size();
    }

    @Override
    public void deleteByDocument(long documentId) {
        chunks.deleteByDocumentId(documentId);
    }

    @Override
    public List<CandidateChunk> findByCandidate(long candidateId) {
        return chunks.findByCandidate(candidateId).stream().map(ResumeEntityMapper::toDomain).toList();
    }

    @Override
    public List<ResumeSnippet> findNearest(float[] embedding, int limit) {
        return chunks.findNearest(embedding, Limit.of(limit)).stream().map(ResumeEntityMapper::toDomain).toList();
    }

    @Override
    public Map<String, Long> countCandidatesByTerms(List<String> terms) {
        return chunks.countCandidatesByTerms(terms);
    }
}
