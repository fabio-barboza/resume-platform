package dev.resumeplatform.resumeai.service;

import java.util.List;

import dev.resumeplatform.resumeai.db.Candidate;
import dev.resumeplatform.resumeai.db.CandidateChunkRow;

public record CandidateWithResume(
        long id,
        String name,
        String email,
        String phone,
        List<CandidateChunkRow> chunks) {
    static CandidateWithResume of(Candidate candidate, List<CandidateChunkRow> chunks) {
        return new CandidateWithResume(candidate.getId(), candidate.getName(), candidate.getEmail(),
                candidate.getPhone(), chunks);
    }
}
