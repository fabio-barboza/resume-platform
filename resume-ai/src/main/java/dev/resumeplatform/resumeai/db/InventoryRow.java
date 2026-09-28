package dev.resumeplatform.resumeai.db;

public record InventoryRow(
        Long id,
        String filename,
        String status,
        Long candidateId,
        String name,
        String email,
        String phone) {
}
