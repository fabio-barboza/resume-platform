package dev.resumeplatform.resumeai.core.usecase.candidate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;

@Service
public class CountCandidatesBySkillUseCase {
    public static final int MAX_SKILL_TERMS = 10;

    private final ChunkGateway chunks;

    public CountCandidatesBySkillUseCase(ChunkGateway chunks) {
        this.chunks = chunks;
    }

    public static List<String> normalizeSkillTerms(List<String> skills) {
        Set<String> seen = new LinkedHashSet<>();
        for (String skill : skills) {
            if (skill != null && !skill.strip().isEmpty()) {
                seen.add(skill.strip());
            }
        }
        return List.copyOf(seen);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> execute(List<String> skills) {
        List<String> terms = normalizeSkillTerms(skills);
        terms = terms.subList(0, Math.min(MAX_SKILL_TERMS, terms.size()));
        if (terms.isEmpty()) {
            return Map.of();
        }
        return chunks.countCandidatesByTerms(terms);
    }
}
