package dev.resumeplatform.resumeai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatResponse(@Schema(description = "Resposta do agente em markdown.") String content) {
}
