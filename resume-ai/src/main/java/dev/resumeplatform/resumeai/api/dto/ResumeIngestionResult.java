package dev.resumeplatform.resumeai.api.dto;

import dev.resumeplatform.resumeai.service.IngestionOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResumeIngestionResult(
        String filename,
        @Schema(description = "null quando o arquivo falhou.", nullable = true) Long documentId,
        @Schema(nullable = true) Long candidateId,
        @Schema(description = "`ingested`, `pending_review` (nenhum email identificado) ou `failed`.") String status,
        long chunkCount,
        @Schema(description = "true quando o arquivo já estava na base e nada foi reprocessado.") boolean duplicate,
        @Schema(description = "Motivo da falha, quando status = `failed`.", nullable = true) String error) {
    public static ResumeIngestionResult from(IngestionOutcome outcome) {
        return new ResumeIngestionResult(outcome.filename(), outcome.documentId(), outcome.candidateId(),
                outcome.status(), outcome.chunkCount(), outcome.duplicate(), null);
    }

    public static ResumeIngestionResult failed(String filename, String error) {
        return new ResumeIngestionResult(filename, null, null, "failed", 0, false, error);
    }
}
