package dev.resumeplatform.resumeai.entrypoint.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatRequest(
        @NotNull @Schema(description = "Identificador da conversa, gerado pelo cliente.") String sessionId,
        @NotNull @Schema(description = "Pergunta do usuário.") String message) {
}
