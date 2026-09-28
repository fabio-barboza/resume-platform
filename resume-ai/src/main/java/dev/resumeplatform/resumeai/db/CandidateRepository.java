package dev.resumeplatform.resumeai.db;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateRepository extends JpaRepository<Candidate, Long>, CandidateRepositoryCustom {
    Optional<Candidate> findFirstByEmailIgnoreCase(String email);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from Candidate c
            where c.id = :id
              and not exists (select 1 from Document d where d.candidate = c)
            """)
    int deleteIfOrphan(@Param("id") Long id);
}
