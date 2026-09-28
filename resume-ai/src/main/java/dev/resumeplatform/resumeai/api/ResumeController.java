package dev.resumeplatform.resumeai.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.resumeplatform.resumeai.api.dto.ErrorResponse;
import dev.resumeplatform.resumeai.api.dto.ResumeIngestionResponse;
import dev.resumeplatform.resumeai.api.dto.ResumeIngestionResult;
import dev.resumeplatform.resumeai.api.dto.ResumeListResponse;
import dev.resumeplatform.resumeai.api.dto.ResumeSummary;
import dev.resumeplatform.resumeai.service.DocumentService;
import dev.resumeplatform.resumeai.service.DomainException;
import dev.resumeplatform.resumeai.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/resumes")
@Tag(name = "Currículos")
@Validated
public class ResumeController {
    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);
    private static final String NO_NAME = "sem-nome.pdf";

    private final IngestionService ingestionService;
    private final DocumentService documentService;

    public ResumeController(IngestionService ingestionService, DocumentService documentService) {
        this.ingestionService = ingestionService;
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingerir um ou mais currículos", description = """
            Extrai nome, email e telefone do próprio arquivo e grava candidato, \
            documento e chunks. Cada arquivo é processado de forma independente: \
            a falha de um não interrompe os demais. Reenviar um arquivo já \
            ingerido é no-op (`duplicate: true`).""")
    public ResumeIngestionResponse ingestResumes(
            @Parameter(description = "Um ou mais currículos em PDF.") @RequestPart("files") List<MultipartFile> files) {
        List<ResumeIngestionResult> results = new ArrayList<>();
        for (MultipartFile upload : files) {
            String filename = filenameOf(upload);
            try {
                results.add(ResumeIngestionResult.from(ingestionService.ingest(filename, upload.getBytes())));
            } catch (DomainException ex) {
                results.add(ResumeIngestionResult.failed(filename, ex.getMessage()));
            } catch (IOException | RuntimeException ex) {
                log.error("Falha inesperada ao ingerir '{}'", filename, ex);
                results.add(ResumeIngestionResult.failed(filename, String.valueOf(ex.getMessage())));
            }
        }
        return new ResumeIngestionResponse(results);
    }

    @PutMapping(path = "/{document_id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Substituir um currículo por inteiro", description = """
            Troca o arquivo preservando o `document_id`: remove os chunks \
            antigos, reingere e reexecuta a extração. O cadastro do candidato \
            passa a valer exatamente o que foi extraído do novo arquivo. \
            Reenviar o mesmo arquivo é no-op.""")
    @ApiResponse(responseCode = "404", description = "Documento inexistente.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "O arquivo enviado já pertence a outro documento.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "PDF ilegível ou sem texto.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResumeIngestionResult replaceResume(
            @Parameter(description = "ID do documento a substituir.") @PathVariable("document_id") long documentId,
            @Parameter(description = "Novo currículo em PDF.") @RequestPart("file") MultipartFile file)
            throws IOException {
        return ResumeIngestionResult.from(ingestionService.replace(documentId, filenameOf(file), file.getBytes()));
    }

    @GetMapping
    @Operation(summary = "Inventário da base de currículos")
    public ResumeListResponse listResumes(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        var page = documentService.listResumes(limit, offset);
        return new ResumeListResponse(page.total(), page.rows().stream().map(ResumeSummary::from).toList());
    }

    @GetMapping("/{document_id}")
    @Operation(summary = "Detalhe do currículo e do candidato vinculado")
    @ApiResponse(responseCode = "404", description = "Documento inexistente.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResumeSummary getResume(@PathVariable("document_id") long documentId) {
        return ResumeSummary.from(documentService.getResume(documentId));
    }

    @DeleteMapping("/{document_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover o currículo e seus chunks", description = """
            Remove o documento e, por cascata, seus chunks. Se o candidato ficar \
            sem nenhum currículo, ele também é removido.""")
    @ApiResponse(responseCode = "404", description = "Documento inexistente.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public void deleteResume(@PathVariable("document_id") long documentId) {
        documentService.deleteResume(documentId);
    }

    private static String filenameOf(MultipartFile upload) {
        String original = upload.getOriginalFilename();
        return original == null || original.isBlank() ? NO_NAME : original;
    }
}
