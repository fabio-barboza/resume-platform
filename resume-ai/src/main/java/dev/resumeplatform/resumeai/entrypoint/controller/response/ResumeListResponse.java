package dev.resumeplatform.resumeai.entrypoint.controller.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record ResumeListResponse(
        @Schema(description = "Total de currículos indexados.") long total,
        List<ResumeResponse> items) {
}
