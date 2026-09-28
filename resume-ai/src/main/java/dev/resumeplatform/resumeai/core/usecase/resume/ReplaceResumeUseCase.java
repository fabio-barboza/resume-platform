package dev.resumeplatform.resumeai.core.usecase.resume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.AnalyzedResume;
import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.IngestionOutcome;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.exception.ConflictException;
import dev.resumeplatform.resumeai.core.domain.exception.DuplicateFileException;
import dev.resumeplatform.resumeai.core.domain.exception.NotFoundException;
import dev.resumeplatform.resumeai.core.gateway.CandidateGateway;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.gateway.StorageGateway;
import dev.resumeplatform.resumeai.core.service.CandidateResolution;
import dev.resumeplatform.resumeai.core.service.ResumePreparation;
import dev.resumeplatform.resumeai.core.support.chunking.FileHash;

@Service
public class ReplaceResumeUseCase {
    private final ResumeGateway resumes;
    private final CandidateGateway candidates;
    private final ChunkGateway chunks;
    private final StorageGateway storage;
    private final ResumePreparation resumePreparation;
    private final CandidateResolution candidateResolution;
    private final TransactionTemplate transaction;

    public ReplaceResumeUseCase(ResumeGateway resumes, CandidateGateway candidates,
            ChunkGateway chunks, StorageGateway storage, ResumePreparation resumePreparation,
            CandidateResolution candidateResolution, TransactionTemplate transaction) {
        this.resumes = resumes;
        this.candidates = candidates;
        this.chunks = chunks;
        this.storage = storage;
        this.resumePreparation = resumePreparation;
        this.candidateResolution = candidateResolution;
        this.transaction = transaction;
    }

    public IngestionOutcome execute(long documentId, String filename, byte[] content) {
        String digest = FileHash.sha256(content);

        Resume current = resumes.findById(documentId).orElseThrow(() -> notFound(documentId));
        if (current.fileHash().equals(digest)) {
            return IngestionOutcome.duplicateOf(current);
        }
        resumes.findByFileHash(digest).ifPresent(clash -> {
            throw new ConflictException("O arquivo enviado já pertence ao documento " + clash.id() + ".");
        });

        AnalyzedResume analyzed = resumePreparation.analyze(content, filename);
        long previousCandidateId = current.candidate().id();

        storage.store(filename, digest, content);
        IngestionOutcome outcome;
        try {
            outcome = transaction.execute(tx -> {
                Candidate candidate = candidateResolution.resolve(analyzed.identity(), previousCandidateId);
                chunks.deleteByDocument(documentId);
                if (!resumes.replaceFile(documentId, candidate.id(), filename, digest, analyzed.pages().size(),
                        analyzed.status())) {
                    throw notFound(documentId);
                }
                long chunkCount = chunks.saveAll(documentId, analyzed.chunks(), analyzed.embeddings());
                if (candidate.id() != previousCandidateId) {
                    candidates.deleteIfOrphan(previousCandidateId);
                }
                return new IngestionOutcome(documentId, filename, analyzed.status(), chunkCount, candidate.id(), false);
            });
        } catch (DuplicateFileException ex) {
            storage.discard(filename, digest);
            long clashId = resumes.findByFileHash(digest).map(Resume::id).orElse(-1L);
            throw new ConflictException("O arquivo enviado já pertence ao documento " + clashId + ".", ex);
        } catch (RuntimeException ex) {
            storage.discard(filename, digest);
            throw ex;
        }

        storage.discard(current.filename(), current.fileHash());
        return outcome;
    }

    private static NotFoundException notFound(long documentId) {
        return new NotFoundException("Documento " + documentId + " não encontrado.");
    }
}
