package dev.resumeplatform.resumeai.entrypoint.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.resumeplatform.resumeai.core.usecase.health.CheckHealthUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Infra")
public class HealthController {
    private final CheckHealthUseCase checkHealth;

    public HealthController(CheckHealthUseCase checkHealth) {
        this.checkHealth = checkHealth;
    }

    @GetMapping("/health")
    @Operation(summary = "Liveness da API e do banco")
    public Map<String, String> health() {
        checkHealth.execute();
        return Map.of("status", "ok");
    }
}
