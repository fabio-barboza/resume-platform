package dev.resumeplatform.resumeai.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.db.CandidateRepository;
import dev.resumeplatform.resumeai.db.DocumentRepository;
import dev.resumeplatform.resumeai.db.InventoryRow;
import dev.resumeplatform.resumeai.db.ResumeRow;
import dev.resumeplatform.resumeai.infra.ResumeStorage;

@Service
public class DocumentService {
    private final DocumentRepository documents;
    private final CandidateRepository candidates;
    private final ResumeStorage storage;
    private final TransactionTemplate transaction;

    public DocumentService(DocumentRepository documents, CandidateRepository candidates, ResumeStorage storage,
            TransactionTemplate transaction) {
        this.documents = documents;
        this.candidates = candidates;
        this.storage = storage;
        this.transaction = transaction;
    }

    public record ResumePage(long total, List<ResumeRow> rows) {
    }

    @Transactional(readOnly = true)
    public ResumePage listResumes(int limit, int offset) {
        return new ResumePage(documents.count(), documents.listRows(limit, offset));
    }

    @Transactional(readOnly = true)
    public ResumeRow getResume(long documentId) {
        return documents.findRowById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
    }

    @Transactional(readOnly = true)
    public List<InventoryRow> listInventory() {
        return documents.findInventory();
    }

    public void deleteResume(long documentId) {
        ResumeRow document = transaction.execute(tx -> {
            ResumeRow found = documents.findRowById(documentId)
                    .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
            documents.deleteDocument(documentId);
            candidates.deleteIfOrphan(found.candidateId());
            return found;
        });
        storage.discard(document.filename(), document.fileHash());
    }
}
