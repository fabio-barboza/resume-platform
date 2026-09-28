package dev.resumeplatform.resumeai.core.usecase.resume;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.AnalyzedResume;
import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.IngestionOutcome;
import dev.resumeplatform.resumeai.core.domain.exception.DuplicateFileException;
import dev.resumeplatform.resumeai.core.gateway.ChunkGateway;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.gateway.StorageGateway;
import dev.resumeplatform.resumeai.core.service.CandidateResolution;
import dev.resumeplatform.resumeai.core.service.ResumePreparation;
import dev.resumeplatform.resumeai.core.support.chunking.FileHash;

@Service
public class IngestResumeUseCase {
    private final ResumeGateway resumes;
    private final ChunkGateway chunks;
    private final StorageGateway storage;
    private final ResumePreparation resumePreparation;
    private final CandidateResolution candidateResolution;
    private final TransactionTemplate transaction;

    public IngestResumeUseCase(ResumeGateway resumes, ChunkGateway chunks, StorageGateway storage,
            ResumePreparation resumePreparation, CandidateResolution candidateResolution,
            TransactionTemplate transaction) {
        this.resumes = resumes;
        this.chunks = chunks;
        this.storage = storage;
        this.resumePreparation = resumePreparation;
        this.candidateResolution = candidateResolution;
        this.transaction = transaction;
    }

    public IngestionOutcome execute(String filename, byte[] content) {
        String digest = FileHash.sha256(content);

        var already = resumes.findByFileHash(digest);
        if (already.isPresent()) {
            return IngestionOutcome.duplicateOf(already.get());
        }

        AnalyzedResume analyzed = resumePreparation.analyze(content, filename);

        storage.store(filename, digest, content);
        try {
            return transaction.execute(tx -> {
                Candidate candidate = candidateResolution.resolve(analyzed.identity(), null);
                long documentId = resumes.create(candidate.id(), filename, digest, analyzed.pages().size(),
                        analyzed.status());
                long chunkCount = chunks.saveAll(documentId, analyzed.chunks(), analyzed.embeddings());
                return new IngestionOutcome(documentId, filename, analyzed.status(), chunkCount, candidate.id(), false);
            });
        } catch (DuplicateFileException ex) {
            storage.discard(filename, digest);
            return IngestionOutcome.duplicateOf(resumes.findByFileHash(digest).orElseThrow());
        } catch (RuntimeException ex) {
            storage.discard(filename, digest);
            throw ex;
        }
    }
}
