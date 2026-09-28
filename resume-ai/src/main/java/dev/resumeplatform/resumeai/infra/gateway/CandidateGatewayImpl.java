package dev.resumeplatform.resumeai.infra.gateway;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.domain.exception.EmailAlreadyInUseException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.infra.entity.CandidateEntity;
import dev.resumeplatform.resumeai.infra.mapper.ResumeEntityMapper;
import dev.resumeplatform.resumeai.infra.repository.CandidateRepository;
import dev.resumeplatform.resumeai.infra.repository.CandidateSearchRepository;
import dev.resumeplatform.resumeai.infra.support.UniqueViolation;

@Component
public class CandidateGatewayImpl implements CandidateGateway {
    private final CandidateRepository candidates;
    private final CandidateSearchRepository search;

    public CandidateGatewayImpl(CandidateRepository candidates, CandidateSearchRepository search) {
        this.candidates = candidates;
        this.search = search;
    }

    @Override
    public Optional<Candidate> findById(long id) {
        return candidates.findById(id).map(ResumeEntityMapper::toDomain);
    }

    @Override
    public Optional<Candidate> findByEmail(String email) {
        return candidates.findFirstByEmailIgnoreCase(email).map(ResumeEntityMapper::toDomain);
    }

    @Override
    public List<Candidate> searchByName(String term, int limit) {
        return search.searchByName(term, limit).stream().map(ResumeEntityMapper::toDomain).toList();
    }

    @Override
    public Candidate create(CandidateIdentity identity) {
        return ResumeEntityMapper.toDomain(
                candidates.saveAndFlush(new CandidateEntity(identity.name(), identity.email(), identity.phone())));
    }

    @Override
    public Optional<Candidate> replace(long id, CandidateIdentity identity) {
        Optional<CandidateEntity> found = candidates.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CandidateEntity candidate = found.get();
        candidate.replaceWith(identity.name(), identity.email(), identity.phone());
        try {
            candidates.flush();
        } catch (DataIntegrityViolationException ex) {
            if (UniqueViolation.isCause(ex)) {
                throw new EmailAlreadyInUseException(identity.email(), ex);
            }
            throw ex;
        }
        return Optional.of(ResumeEntityMapper.toDomain(candidate));
    }

    @Override
    public void deleteIfOrphan(long id) {
        candidates.deleteIfOrphan(id);
    }
}
