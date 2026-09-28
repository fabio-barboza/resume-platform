package dev.resumeplatform.resumeai.db;

import java.util.List;

public interface CandidateRepositoryCustom {
    List<Candidate> searchByName(String term, int limit);
}
