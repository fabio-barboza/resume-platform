package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.resumeplatform.resumeai.infra.repository.entity.ChunkEntity;
import dev.resumeplatform.resumeai.infra.repository.projection.CandidateChunkRow;
import dev.resumeplatform.resumeai.infra.repository.projection.SimilarChunkRow;

public interface ChunkRepository extends JpaRepository<ChunkEntity, String>, ChunkRepositoryCustom {
    long countByDocument_Id(Long documentId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ChunkEntity c where c.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Query("""
            select new dev.resumeplatform.resumeai.infra.repository.projection.CandidateChunkRow(
                c.content, c.page, c.chunkIndex, d.id, d.filename)
            from ChunkEntity c join c.document d
            where d.candidate.id = :candidateId
            order by d.id, c.page, c.chunkIndex
            """)
    List<CandidateChunkRow> findByCandidate(@Param("candidateId") Long candidateId);

    @Query("""
            select new dev.resumeplatform.resumeai.infra.repository.projection.SimilarChunkRow(
                c.content, c.page, c.chunkIndex, d.id, d.filename,
                ca.id, ca.name, ca.email, cosine_distance(c.embedding, :embedding))
            from ChunkEntity c join c.document d join d.candidate ca
            order by cosine_distance(c.embedding, :embedding)
            """)
    List<SimilarChunkRow> findNearest(@Param("embedding") float[] embedding, Limit limit);
}
