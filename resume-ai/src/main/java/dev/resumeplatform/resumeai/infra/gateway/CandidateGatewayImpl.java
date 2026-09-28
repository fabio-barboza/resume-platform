package dev.resumeplatform.resumeai.infra.gateway;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.domain.exception.EmailAlreadyInUseException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.infra.repository.CandidateRepository;
import dev.resumeplatform.resumeai.infra.repository.UniqueViolation;
import dev.resumeplatform.resumeai.infra.repository.entity.CandidateEntity;
import dev.resumeplatform.resumeai.infra.repository.mapper.ResumeEntityMapper;

@Component
public class CandidateGatewayImpl implements CandidateGateway {
    private final CandidateRepository candidates;

    public CandidateGatewayImpl(CandidateRepository candidates) {
        this.candidates = candidates;
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
        return candidates.searchByName(term, limit).stream().map(ResumeEntityMapper::toDomain).toList();
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
