package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;
import java.util.Map;

public interface ChunkRepositoryCustom {
    Map<String, Long> countCandidatesByTerms(List<String> terms);
}
