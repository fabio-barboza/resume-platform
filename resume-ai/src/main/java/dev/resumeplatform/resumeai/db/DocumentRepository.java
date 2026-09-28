package dev.resumeplatform.resumeai.db;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long>, DocumentRepositoryCustom {
    String RESUME_ROW = """
            select new dev.resumeplatform.resumeai.db.ResumeRow(
                d.id, c.id, d.filename, d.fileHash, d.pages, d.status, d.ingestedAt,
                c.name, c.email, c.phone, c.createdAt,
                (select count(ch) from Chunk ch where ch.document = d))
            from Document d join d.candidate c
            """;

    @Query(RESUME_ROW + " where d.id = :id")
    Optional<ResumeRow> findRowById(@Param("id") Long id);

    @Query(RESUME_ROW + " where d.fileHash = :fileHash")
    Optional<ResumeRow> findRowByFileHash(@Param("fileHash") String fileHash);

    @Query("""
            select new dev.resumeplatform.resumeai.db.InventoryRow(
                d.id, d.filename, d.status, c.id, c.name, c.email, c.phone)
            from Document d join d.candidate c
            order by coalesce(c.name, d.filename), d.id
            """)
    List<InventoryRow> findInventory();

    Optional<Document> findFirstByCandidate_IdOrderByIngestedAtDesc(Long candidateId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Document d where d.id = :id")
    int deleteDocument(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("update Document d set d.ingestedAt = current_timestamp where d.id = :id")
    int touchIngestedAt(@Param("id") Long id);
}
