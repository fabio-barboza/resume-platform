package dev.resumeplatform.resumeai.infra.repository.projection;

public record InventoryRow(
        Long id,
        String filename,
        String status,
        Long candidateId,
        String name,
        String email,
        String phone) {
}
