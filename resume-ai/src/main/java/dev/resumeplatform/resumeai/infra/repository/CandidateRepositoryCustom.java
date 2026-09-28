package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;

import dev.resumeplatform.resumeai.infra.repository.entity.CandidateEntity;

public interface CandidateRepositoryCustom {
    List<CandidateEntity> searchByName(String term, int limit);
}
