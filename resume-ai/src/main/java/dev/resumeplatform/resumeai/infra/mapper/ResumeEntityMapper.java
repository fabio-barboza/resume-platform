package dev.resumeplatform.resumeai.infra.mapper;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.ResumeSnippet;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.infra.dto.CandidateChunkDto;
import dev.resumeplatform.resumeai.infra.dto.InventoryDto;
import dev.resumeplatform.resumeai.infra.dto.ResumeDto;
import dev.resumeplatform.resumeai.infra.dto.SimilarChunkDto;
import dev.resumeplatform.resumeai.infra.entity.CandidateEntity;

public final class ResumeEntityMapper {
    private ResumeEntityMapper() {
    }

    public static Candidate toDomain(CandidateEntity entity) {
        return new Candidate(entity.getId(), entity.getName(), entity.getEmail(), entity.getPhone(),
                entity.getCreatedAt());
    }

    public static Resume toDomain(ResumeDto row) {
        return new Resume(row.id(), row.filename(), row.fileHash(), row.pages(), ResumeStatus.fromValue(row.status()),
                row.ingestedAt(), row.chunkCount(), new Candidate(row.candidateId(), row.candidateName(),
                        row.candidateEmail(), row.candidatePhone(), row.candidateCreatedAt()));
    }

    public static InventoryEntry toDomain(InventoryDto row) {
        return new InventoryEntry(row.id(), row.filename(), ResumeStatus.fromValue(row.status()), row.candidateId(),
                row.name(), row.email(), row.phone());
    }

    public static CandidateChunk toDomain(CandidateChunkDto row) {
        return new CandidateChunk(row.content(), row.page(), row.chunkIndex(), row.documentId(), row.filename());
    }

    public static ResumeSnippet toDomain(SimilarChunkDto row) {
        return new ResumeSnippet(row.content(), row.page(), row.chunkIndex(), row.documentId(), row.filename(),
                row.candidateId(), row.candidateName(), row.candidateEmail(), row.distance());
    }
}
