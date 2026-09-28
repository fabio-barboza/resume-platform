package dev.resumeplatform.resumeai.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.resumeplatform.resumeai.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Infra")
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    @Operation(summary = "Liveness da API e do banco")
    public Map<String, String> health() {
        healthService.check();
        return Map.of("status", "ok");
    }
}
