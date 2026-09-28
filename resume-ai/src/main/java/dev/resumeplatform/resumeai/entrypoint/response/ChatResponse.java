package dev.resumeplatform.resumeai.entrypoint.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatResponse(@Schema(description = "Resposta do agente em markdown.") String content) {
}
