package dev.resumeplatform.resumeai.core.domain;

public record InventoryEntry(
        long id,
        String filename,
        ResumeStatus status,
        long candidateId,
        String name,
        String email,
        String phone) {
}
