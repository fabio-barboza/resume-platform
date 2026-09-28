package dev.resumeplatform.resumeai.infra.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.resumeplatform.resumeai.infra.entity.CandidateEntity;

public interface CandidateRepository extends JpaRepository<CandidateEntity, Long> {
    Optional<CandidateEntity> findFirstByEmailIgnoreCase(String email);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from CandidateEntity c
            where c.id = :id
              and not exists (select 1 from DocumentEntity d where d.candidate = c)
            """)
    int deleteIfOrphan(@Param("id") Long id);
}
