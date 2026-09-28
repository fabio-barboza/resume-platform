package dev.resumeplatform.resumeai.entrypoint.response;

import java.util.List;

public record ResumeIngestionResponse(List<IngestionResultResponse> results) {
}
