package dev.resumeplatform.resumeai.entrypoint.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(example = """
        {"name": "Rafael Mendes", "email": "rafael.mendes@example.com", "phone": "(11) 98888-7777"}""")
public record ReplaceCandidateRequest(
        @Schema(description = "Nome; ausente = null.", nullable = true) String name,
        @Schema(description = "Email; chave natural do candidato. Ausente = null.", nullable = true) String email,
        @Schema(description = "Telefone; ausente = null.", nullable = true) String phone) {
}
