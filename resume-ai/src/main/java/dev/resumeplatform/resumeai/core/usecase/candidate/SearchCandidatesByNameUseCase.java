package dev.resumeplatform.resumeai.core.usecase.candidate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateWithResume;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;

@Service
public class SearchCandidatesByNameUseCase {
    static final int DEFAULT_LIMIT = 10;

    private final CandidateGateway candidates;
    private final ChunkGateway chunks;

    public SearchCandidatesByNameUseCase(CandidateGateway candidates, ChunkGateway chunks) {
        this.candidates = candidates;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public List<CandidateWithResume> execute(String term) {
        List<CandidateWithResume> found = new ArrayList<>();
        for (Candidate candidate : candidates.searchByName(term, DEFAULT_LIMIT)) {
            found.add(CandidateWithResume.of(candidate, chunks.findByCandidate(candidate.id())));
        }
        return found;
    }
}
