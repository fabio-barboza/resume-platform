package dev.resumeplatform.resumeai.api;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.resumeplatform.resumeai.api.dto.CandidateReplaceRequest;
import dev.resumeplatform.resumeai.api.dto.CandidateSummary;
import dev.resumeplatform.resumeai.api.dto.ErrorResponse;
import dev.resumeplatform.resumeai.service.CandidateService;
import dev.resumeplatform.resumeai.service.ResumeFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/candidates")
@Tag(name = "Candidatos")
public class CandidateController {
    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @PutMapping("/{candidate_id}")
    @Operation(summary = "Substituir o cadastro do candidato por inteiro", description = """
            Substituição total: campo ausente no body vira null. Não toca em \
            chunks nem em embeddings — a contagem de chunks do candidato não \
            muda. Atenção: uma nova ingestão de currículo sobrescreve estes \
            dados, porque o arquivo é a fonte da verdade do cadastro.""")
    @ApiResponse(responseCode = "404", description = "Candidato inexistente.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "O email já pertence a outro candidato.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CandidateSummary replaceCandidate(@RequestBody CandidateReplaceRequest payload,
            @Parameter(description = "ID do candidato.") @PathVariable("candidate_id") long candidateId) {
        return CandidateSummary.from(
                candidateService.replaceCandidate(candidateId, payload.name(), payload.email(), payload.phone()));
    }

    @GetMapping("/{identifier}/resume")
    @Operation(summary = "Baixar o PDF do currículo do candidato", description = """
            Aceita o ID numérico ou o email do candidato. Se ele tiver mais de \
            um currículo vinculado, devolve o mais recente.""")
    @ApiResponse(responseCode = "200", description = "PDF do currículo.",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
    @ApiResponse(responseCode = "404", description = "Candidato, currículo ou arquivo inexistente.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<byte[]> downloadResume(
            @Parameter(description = "ID numérico ou email do candidato.") @PathVariable String identifier) {
        ResumeFile file = candidateService.getResumeFile(identifier);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }
}
