package dev.resumeplatform.resumeai.entrypoint.response;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IngestionResultResponse(
        String filename,
        @Schema(description = "null quando o arquivo falhou.", nullable = true) Long documentId,
        @Schema(nullable = true) Long candidateId,
        @Schema(description = "`ingested`, `pending_review` (nenhum email identificado) ou `failed`.") String status,
        long chunkCount,
        @Schema(description = "true quando o arquivo já estava na base e nada foi reprocessado.") boolean duplicate,
        @Schema(description = "Motivo da falha, quando status = `failed`.", nullable = true) String error) {
}
