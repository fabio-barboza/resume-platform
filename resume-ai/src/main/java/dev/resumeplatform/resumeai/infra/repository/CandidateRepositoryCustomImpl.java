package dev.resumeplatform.resumeai.infra.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.resumeplatform.resumeai.infra.repository.entity.CandidateEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

class CandidateRepositoryCustomImpl implements CandidateRepositoryCustom {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
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
