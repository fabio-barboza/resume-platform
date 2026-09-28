package dev.resumeplatform.resumeai.entrypoint.controller.response;

import java.util.List;

public record ResumeIngestionResponse(List<IngestionResultResponse> results) {
}
