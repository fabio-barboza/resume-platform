package dev.resumeplatform.resumeai.core.usecase.candidate;

import org.springframework.stereotype.Service;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.ResumeFile;
import dev.resumeplatform.resumeai.core.domain.StoredFile;
import dev.resumeplatform.resumeai.core.domain.exception.NotFoundException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.gateway.StorageGateway;

@Service
public class DownloadResumeUseCase {
    private final CandidateGateway candidates;
    private final ResumeGateway resumes;
    private final StorageGateway storage;

    public DownloadResumeUseCase(CandidateGateway candidates, ResumeGateway resumes,
            StorageGateway storage) {
        this.candidates = candidates;
        this.resumes = resumes;
        this.storage = storage;
    }

    public ResumeFile execute(String identifier) {
        Candidate candidate = (identifier.chars().allMatch(Character::isDigit) && !identifier.isEmpty()
                ? candidates.findById(Long.parseLong(identifier))
                : candidates.findByEmail(identifier))
                .orElseThrow(() -> new NotFoundException("Candidato '" + identifier + "' não encontrado."));
        StoredFile file = resumes.findLatestFileOf(candidate.id())
                .orElseThrow(() -> new NotFoundException("Candidato '" + identifier + "' não tem currículo."));

        byte[] content = storage.fetch(file.filename(), file.fileHash())
                .orElseThrow(() -> new NotFoundException(
                        "Arquivo do currículo de '" + identifier + "' não está disponível no bucket."));
        return new ResumeFile(content, file.filename());
    }
}
