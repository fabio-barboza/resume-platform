package dev.resumeplatform.resumeai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.db.Candidate;
import dev.resumeplatform.resumeai.db.CandidateRepository;
import dev.resumeplatform.resumeai.db.ChunkRepository;
import dev.resumeplatform.resumeai.db.Document;
import dev.resumeplatform.resumeai.db.DocumentRepository;
import dev.resumeplatform.resumeai.db.UniqueViolation;
import dev.resumeplatform.resumeai.infra.ResumeStorage;

@Service
public class CandidateService {
    public static final int MAX_SKILL_TERMS = 10;

    private final CandidateRepository candidates;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ResumeStorage storage;

    public CandidateService(CandidateRepository candidates, DocumentRepository documents, ChunkRepository chunks,
            ResumeStorage storage) {
        this.candidates = candidates;
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
    }

    public ResumeFile getResumeFile(String identifier) {
        Candidate candidate = (identifier.chars().allMatch(Character::isDigit) && !identifier.isEmpty()
                ? candidates.findById(Long.parseLong(identifier))
                : candidates.findFirstByEmailIgnoreCase(identifier))
                .orElseThrow(() -> new NotFoundException("Candidato '" + identifier + "' não encontrado."));
        Document document = documents.findFirstByCandidate_IdOrderByIngestedAtDesc(candidate.getId())
                .orElseThrow(() -> new NotFoundException("Candidato '" + identifier + "' não tem currículo."));

        byte[] content = storage.fetch(document.getFilename(), document.getFileHash())
                .orElseThrow(() -> new NotFoundException(
                        "Arquivo do currículo de '" + identifier + "' não está disponível no bucket."));
        return new ResumeFile(content, document.getFilename());
    }

    @Transactional(readOnly = true)
    public List<CandidateWithResume> searchByName(String term) {
        return searchByName(term, 10);
    }

    @Transactional(readOnly = true)
    public List<CandidateWithResume> searchByName(String term, int limit) {
        List<CandidateWithResume> found = new ArrayList<>();
        for (Candidate candidate : candidates.searchByName(term, limit)) {
            found.add(CandidateWithResume.of(candidate, chunks.findByCandidate(candidate.getId())));
        }
        return found;
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
    public Map<String, Long> countBySkill(List<String> skills) {
        List<String> terms = normalizeSkillTerms(skills);
        terms = terms.subList(0, Math.min(MAX_SKILL_TERMS, terms.size()));
        if (terms.isEmpty()) {
            return Map.of();
        }
        return chunks.countCandidatesByTerms(terms);
    }

    @Transactional
    public Candidate replaceCandidate(long candidateId, String name, String email, String phone) {
        Candidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new NotFoundException("Candidato " + candidateId + " não encontrado."));
        candidate.replaceWith(name, email, phone);
        try {
            candidates.flush();
        } catch (DataIntegrityViolationException ex) {
            if (UniqueViolation.isCause(ex)) {
                throw new ConflictException("O email '" + email + "' já pertence a outro candidato.", ex);
            }
            throw ex;
        }
        return candidate;
    }
}
