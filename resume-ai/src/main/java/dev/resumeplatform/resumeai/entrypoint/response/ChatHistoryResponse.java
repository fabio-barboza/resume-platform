package dev.resumeplatform.resumeai.entrypoint.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatHistoryResponse(
        @Schema(description = "A sessão consultada.") String sessionId,
        @Schema(description = "Vazia quando a sessão não existe — não é erro.") List<Message> messages) {
    public record Message(
            @Schema(description = "`user` ou `assistant`.") String role,
            @Schema(description = "Texto da fala, em markdown.") String content) {
    }
}
