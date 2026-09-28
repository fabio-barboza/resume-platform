package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;

import dev.resumeplatform.resumeai.infra.repository.projection.ResumeRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

class DocumentRepositoryCustomImpl implements DocumentRepositoryCustom {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<ResumeRow> listRows(int limit, int offset) {
        return entityManager.createQuery(DocumentRepository.RESUME_ROW + " order by d.filename", ResumeRow.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }
}
