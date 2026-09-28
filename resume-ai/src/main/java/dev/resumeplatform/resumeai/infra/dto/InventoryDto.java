package dev.resumeplatform.resumeai.infra.dto;

public record InventoryDto(
        Long id,
        String filename,
        String status,
        Long candidateId,
        String name,
        String email,
        String phone) {
}
