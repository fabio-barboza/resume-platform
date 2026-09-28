package dev.resumeplatform.resumeai.api.dto;

import java.util.List;

public record ResumeIngestionResponse(List<ResumeIngestionResult> results) {
}
