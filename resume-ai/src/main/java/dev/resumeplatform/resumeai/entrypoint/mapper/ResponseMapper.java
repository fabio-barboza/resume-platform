package dev.resumeplatform.resumeai.entrypoint.mapper;

import java.util.List;

import dev.resumeplatform.resumeai.core.domain.Candidate;
import dev.resumeplatform.resumeai.core.domain.IngestionOutcome;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.ResumePage;
import dev.resumeplatform.resumeai.core.domain.chat.ChatTurn;
import dev.resumeplatform.resumeai.entrypoint.response.CandidateResponse;
import dev.resumeplatform.resumeai.entrypoint.response.ChatHistoryResponse;
import dev.resumeplatform.resumeai.entrypoint.response.IngestionResultResponse;
import dev.resumeplatform.resumeai.entrypoint.response.ResumeListResponse;
import dev.resumeplatform.resumeai.entrypoint.response.ResumeResponse;

public final class ResponseMapper {
    static final String FAILED = "failed";

    private ResponseMapper() {
    }

    public static CandidateResponse toResponse(Candidate candidate) {
        return new CandidateResponse(candidate.id(), candidate.name(), candidate.email(), candidate.phone(),
                candidate.createdAt());
    }

    public static ResumeResponse toResponse(Resume resume) {
        return new ResumeResponse(resume.id(), resume.filename(), resume.fileHash(), resume.pages(),
                resume.status().value(), resume.ingestedAt(), resume.chunkCount(), toResponse(resume.candidate()));
    }

    public static ResumeListResponse toResponse(ResumePage page) {
        return new ResumeListResponse(page.total(), page.items().stream().map(ResponseMapper::toResponse).toList());
    }

    public static IngestionResultResponse toResult(IngestionOutcome outcome) {
        return new IngestionResultResponse(outcome.filename(), outcome.documentId(), outcome.candidateId(),
                outcome.status().value(), outcome.chunkCount(), outcome.duplicate(), null);
    }

    public static IngestionResultResponse failed(String filename, String error) {
        return new IngestionResultResponse(filename, null, null, FAILED, 0, false, error);
    }

    public static ChatHistoryResponse toResponse(String sessionId, List<ChatTurn> turns) {
        return new ChatHistoryResponse(sessionId, turns.stream()
                .map(turn -> new ChatHistoryResponse.Message(turn.role(), turn.content()))
                .toList());
    }
}
