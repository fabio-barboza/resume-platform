package dev.resumeplatform.resumeai.infra.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Repository;

import dev.resumeplatform.resumeai.infra.support.LikePatterns;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Contagem literal por termo: uma subquery por termo numa só ida ao banco — dinâmica demais para um {@code @Query}. */
@Repository
public class ChunkCountRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public Map<String, Long> countCandidatesByTerms(List<String> terms) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (terms.isEmpty()) {
            return counts;
        }

        StringJoiner columns = new StringJoiner(", ", "SELECT ", "");
        for (int i = 0; i < terms.size(); i++) {
            columns.add("""
                    (SELECT count(DISTINCT d.candidate_id)
                       FROM chunks c JOIN documents d ON d.id = c.document_id
                      WHERE unaccent(lower(c.content)) LIKE '%%' || unaccent(lower(:term%d)) || '%%' ESCAPE '\\')
                    """.formatted(i));
        }

        var query = entityManager.createNativeQuery(columns.toString());
        for (int i = 0; i < terms.size(); i++) {
            query.setParameter("term" + i, LikePatterns.escape(terms.get(i)));
        }

        Object result = query.getSingleResult();
        Object[] row = result instanceof Object[] array ? array : new Object[] {result};
        for (int i = 0; i < terms.size(); i++) {
            counts.put(terms.get(i), ((Number) row[i]).longValue());
        }
        return counts;
    }
}
