package dev.resumeplatform.resumeai.core.domain.service;

import org.springframework.stereotype.Service;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;

/** O currículo é a fonte da verdade do cadastro: o candidato resolvido passa a valer o que foi extraído. */
@Service
public class CandidateResolution {
    private final CandidateGateway candidates;

    public CandidateResolution(CandidateGateway candidates) {
        this.candidates = candidates;
    }

    public Candidate resolve(CandidateIdentity identity, Long fallbackId) {
        Candidate target = identity.email() != null ? candidates.findByEmail(identity.email()).orElse(null) : null;
        if (target == null && fallbackId != null) {
            target = candidates.findById(fallbackId).orElse(null);
        }
        if (target == null) {
            return candidates.create(identity);
        }
        long id = target.id();
        return candidates.replace(id, identity)
                .orElseThrow(() -> new IllegalStateException("Candidato " + id + " sumiu no meio da ingestão."));
    }
}
