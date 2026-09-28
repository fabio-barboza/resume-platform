package dev.resumeplatform.resumeai.core.usecase.resume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.exception.NotFoundException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.gateway.StorageGateway;

@Service
public class DeleteResumeUseCase {
    private final ResumeGateway resumes;
    private final CandidateGateway candidates;
    private final StorageGateway storage;
    private final TransactionTemplate transaction;

    public DeleteResumeUseCase(ResumeGateway resumes, CandidateGateway candidates,
            StorageGateway storage, TransactionTemplate transaction) {
        this.resumes = resumes;
        this.candidates = candidates;
        this.storage = storage;
        this.transaction = transaction;
    }

    public void execute(long documentId) {
        Resume document = transaction.execute(tx -> {
            Resume found = resumes.findById(documentId)
                    .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
            resumes.delete(documentId);
            candidates.deleteIfOrphan(found.candidate().id());
            return found;
        });
        storage.discard(document.filename(), document.fileHash());
    }
}
