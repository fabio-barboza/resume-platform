package dev.resumeplatform.resumeai.core.usecase.candidate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.domain.exception.NotFoundException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;

@Service
public class ReplaceCandidateUseCase {
    private final CandidateGateway candidates;

    public ReplaceCandidateUseCase(CandidateGateway candidates) {
        this.candidates = candidates;
    }

    @Transactional
    public Candidate execute(long candidateId, String name, String email, String phone) {
        return candidates.replace(candidateId, new CandidateIdentity(name, email, phone))
                .orElseThrow(() -> new NotFoundException("Candidato " + candidateId + " não encontrado."));
    }
}
