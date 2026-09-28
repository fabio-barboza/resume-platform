package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.resumeplatform.resumeai.infra.dto.InventoryDto;
import dev.resumeplatform.resumeai.infra.dto.ResumeDto;
import dev.resumeplatform.resumeai.infra.entity.DocumentEntity;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
    String RESUME_DTO = """
            select new dev.resumeplatform.resumeai.infra.dto.ResumeDto(
                d.id, c.id, d.filename, d.fileHash, d.pages, d.status, d.ingestedAt,
                c.name, c.email, c.phone, c.createdAt,
                (select count(ch) from ChunkEntity ch where ch.document = d))
            from DocumentEntity d join d.candidate c
            """;

    @Query(RESUME_DTO + " order by d.filename limit :limit offset :offset")
    List<ResumeDto> listRows(@Param("limit") int limit, @Param("offset") int offset);

    @Query(RESUME_DTO + " where d.id = :id")
    Optional<ResumeDto> findRowById(@Param("id") Long id);

    @Query(RESUME_DTO + " where d.fileHash = :fileHash")
    Optional<ResumeDto> findRowByFileHash(@Param("fileHash") String fileHash);

    @Query("""
            select new dev.resumeplatform.resumeai.infra.dto.InventoryDto(
                d.id, d.filename, d.status, c.id, c.name, c.email, c.phone)
            from DocumentEntity d join d.candidate c
            order by coalesce(c.name, d.filename), d.id
            """)
    List<InventoryDto> findInventory();

    Optional<DocumentEntity> findFirstByCandidate_IdOrderByIngestedAtDesc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from DocumentEntity d where d.id = :id")
    int deleteDocument(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update DocumentEntity d set d.ingestedAt = current_timestamp where d.id = :id")
    int touchIngestedAt(@Param("id") Long id);
}
