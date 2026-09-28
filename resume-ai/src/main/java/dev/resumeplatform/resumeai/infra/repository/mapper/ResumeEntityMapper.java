package dev.resumeplatform.resumeai.infra.repository.mapper;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.infra.repository.entity.CandidateEntity;
import dev.resumeplatform.resumeai.infra.repository.projection.CandidateChunkRow;
import dev.resumeplatform.resumeai.infra.repository.projection.InventoryRow;
import dev.resumeplatform.resumeai.infra.repository.projection.ResumeRow;
import dev.resumeplatform.resumeai.infra.repository.projection.SimilarChunkRow;

public final class ResumeEntityMapper {
    private ResumeEntityMapper() {
    }

    public static Candidate toDomain(CandidateEntity entity) {
        return new Candidate(entity.getId(), entity.getName(), entity.getEmail(), entity.getPhone(),
                entity.getCreatedAt());
    }

    public static Resume toDomain(ResumeRow row) {
        return new Resume(row.id(), row.filename(), row.fileHash(), row.pages(), ResumeStatus.fromValue(row.status()),
                row.ingestedAt(), row.chunkCount(), new Candidate(row.candidateId(), row.candidateName(),
                        row.candidateEmail(), row.candidatePhone(), row.candidateCreatedAt()));
    }

    public static InventoryEntry toDomain(InventoryRow row) {
        return new InventoryEntry(row.id(), row.filename(), ResumeStatus.fromValue(row.status()), row.candidateId(),
                row.name(), row.email(), row.phone());
    }

    public static CandidateChunk toDomain(CandidateChunkRow row) {
        return new CandidateChunk(row.content(), row.page(), row.chunkIndex(), row.documentId(), row.filename());
    }

    public static ResumeSnippet toDomain(SimilarChunkRow row) {
        return new ResumeSnippet(row.content(), row.page(), row.chunkIndex(), row.documentId(), row.filename(),
                row.candidateId(), row.candidateName(), row.candidateEmail(), row.distance());
    }
}
