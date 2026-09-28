package dev.resumeplatform.resumeai.infra.gateway;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.core.domain.StoredFile;
import dev.resumeplatform.resumeai.core.domain.exception.DuplicateFileException;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.infra.repository.CandidateRepository;
import dev.resumeplatform.resumeai.infra.repository.DocumentRepository;
import dev.resumeplatform.resumeai.infra.repository.entity.DocumentEntity;
import dev.resumeplatform.resumeai.infra.repository.mapper.ResumeEntityMapper;

@Component
public class ResumeGatewayImpl implements ResumeGateway {
    private final DocumentRepository documents;
    private final CandidateRepository candidates;

    public ResumeGatewayImpl(DocumentRepository documents, CandidateRepository candidates) {
        this.documents = documents;
        this.candidates = candidates;
    }

    @Override
    public Optional<Resume> findById(long id) {
        return documents.findRowById(id).map(ResumeEntityMapper::toDomain);
    }

    @Override
    public Optional<Resume> findByFileHash(String fileHash) {
        return documents.findRowByFileHash(fileHash).map(ResumeEntityMapper::toDomain);
    }

    @Override
    public long count() {
        return documents.count();
    }

    @Override
    public List<Resume> list(int limit, int offset) {
        return documents.listRows(limit, offset).stream().map(ResumeEntityMapper::toDomain).toList();
    }

    @Override
    public List<InventoryEntry> inventory() {
        return documents.findInventory().stream().map(ResumeEntityMapper::toDomain).toList();
    }

    @Override
    public Optional<StoredFile> findLatestFileOf(long candidateId) {
        return documents.findFirstByCandidate_IdOrderByIngestedAtDesc(candidateId)
                .map(d -> new StoredFile(d.getFilename(), d.getFileHash()));
    }

    @Override
    public long create(long candidateId, String filename, String fileHash, int pages, ResumeStatus status) {
        try {
            return documents.saveAndFlush(new DocumentEntity(candidates.getReferenceById(candidateId), filename,
                    fileHash, pages, status.value())).getId();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Override
    public boolean replaceFile(long id, long candidateId, String filename, String fileHash, int pages,
            ResumeStatus status) {
        Optional<DocumentEntity> found = documents.findById(id);
        if (found.isEmpty()) {
            return false;
        }
        found.get().replaceFile(candidates.getReferenceById(candidateId), filename, fileHash, pages, status.value());
        try {
            documents.flush();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        documents.touchIngestedAt(id);
        return true;
    }

    @Override
    public void delete(long id) {
        documents.deleteDocument(id);
    }

    private static RuntimeException translate(DataIntegrityViolationException ex) {
        return UniqueViolation.isCause(ex) ? new DuplicateFileException(ex) : ex;
    }
}
