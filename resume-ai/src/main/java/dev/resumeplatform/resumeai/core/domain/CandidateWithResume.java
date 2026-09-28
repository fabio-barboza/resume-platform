package dev.resumeplatform.resumeai.core.domain;

import java.util.List;

public record CandidateWithResume(
        long id,
        String name,
        String email,
        String phone,
        List<CandidateChunk> chunks) {
    public static CandidateWithResume of(Candidate candidate, List<CandidateChunk> chunks) {
        return new CandidateWithResume(candidate.id(), candidate.name(), candidate.email(), candidate.phone(), chunks);
    }
}
