package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;
import java.util.Optional;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;

public interface CandidateGateway {
    Optional<Candidate> findById(long id);

    Optional<Candidate> findByEmail(String email);

    List<Candidate> searchByName(String term, int limit);

    Candidate create(CandidateIdentity identity);

    /**
     * Substitui nome, email e telefone por inteiro. Vazio se o candidato não existe; lança
     * {@link dev.resumeplatform.resumeai.core.domain.exception.EmailAlreadyInUseException} se o email for de outro.
     */
    Optional<Candidate> replace(long id, CandidateIdentity identity);

    void deleteIfOrphan(long id);
}
