package dev.resumeplatform.resumeai.infra.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Repository;

import dev.resumeplatform.resumeai.infra.entity.CandidateEntity;
import dev.resumeplatform.resumeai.infra.support.LikePatterns;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** Busca por nome: uma condição por palavra, sem acento e fora de ordem — dinâmica demais para um {@code @Query}. */
@Repository
public class CandidateSearchRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public List<CandidateEntity> searchByName(String term, int limit) {
        List<String> words = Arrays.stream(term.strip().split("\\s+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(CandidateEntity.class);
        Root<CandidateEntity> candidate = query.from(CandidateEntity.class);

        Expression<String> normalizedName = unaccent(cb, cb.lower(candidate.get("name")));
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.isNotNull(candidate.get("name")));
        for (String word : words) {
            Expression<String> target = unaccent(cb, cb.lower(cb.literal(LikePatterns.escape(word))));
            predicates.add(cb.like(normalizedName, cb.concat(cb.concat("%", target), "%"), '\\'));
        }

        query.select(candidate).where(predicates.toArray(Predicate[]::new)).orderBy(cb.asc(candidate.get("name")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }

    private static Expression<String> unaccent(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("unaccent", String.class, value);
    }
}
