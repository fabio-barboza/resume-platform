package dev.resumeplatform.resumeai.core.domain.service;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.resumeplatform.resumeai.core.domain.AnalyzedResume;
import dev.resumeplatform.resumeai.core.domain.CandidateIdentity;
import dev.resumeplatform.resumeai.core.domain.PreparedResume;
import dev.resumeplatform.resumeai.core.domain.chunking.ResumeChunker;
import dev.resumeplatform.resumeai.core.domain.chunking.TextChunk;
import dev.resumeplatform.resumeai.core.domain.exception.InvalidDocumentException;
import dev.resumeplatform.resumeai.core.domain.guardrail.InjectionDetector;
import dev.resumeplatform.resumeai.core.domain.settings.IngestionSettings;
import dev.resumeplatform.resumeai.core.domain.text.PyRepr;
import dev.resumeplatform.resumeai.core.gateway.ContactExtractionGateway;
import dev.resumeplatform.resumeai.core.gateway.EmbeddingGateway;
import dev.resumeplatform.resumeai.core.gateway.PdfGateway;

/** Lê, valida, extrai o contato e gera os embeddings: o trabalho comum ao POST e ao PUT, todo antes da transação. */
@Service
public class ResumePreparation {
    private static final Logger log = LoggerFactory.getLogger(ResumePreparation.class);

    private final PdfGateway pdfReader;
    private final ContactExtractionGateway extractor;
    private final EmbeddingGateway embeddings;
    private final IngestionSettings settings;

    public ResumePreparation(PdfGateway pdfReader, ContactExtractionGateway extractor, EmbeddingGateway embeddings,
            IngestionSettings settings) {
        this.pdfReader = pdfReader;
        this.extractor = extractor;
        this.embeddings = embeddings;
        this.settings = settings;
    }

    public AnalyzedResume analyze(byte[] content, String filename) {
        PreparedResume prepared = prepare(content, filename);
        CandidateIdentity identity = extract(ResumeChunker.firstPagesText(prepared.pages(), settings.extractionPages()));
        requireIdentity(identity, filename);
        List<float[]> vectors = embeddings.embedAll(prepared.chunks().stream().map(TextChunk::content).toList());
        return new AnalyzedResume(prepared.pages(), prepared.chunks(), vectors, identity);
    }

    public PreparedResume prepare(byte[] content, String filename) {
        List<String> pages;
        try {
            pages = pdfReader.readPages(content);
        } catch (IOException | RuntimeException ex) {
            throw new InvalidDocumentException("Não foi possível ler o PDF '" + filename + "': " + ex.getMessage(), ex);
        }

        if (pages.size() > settings.maxResumePages()) {
            throw new InvalidDocumentException("O arquivo '" + filename + "' tem " + pages.size()
                    + " páginas; o limite é " + settings.maxResumePages()
                    + ". Envie um currículo resumido ou aumente MAX_RESUME_PAGES.");
        }

        List<TextChunk> built = ResumeChunker.buildChunks(pages);
        if (built.isEmpty()) {
            throw new InvalidDocumentException(
                    "O arquivo '" + filename + "' não tem texto extraível (PDF de imagem escaneada?).");
        }

        var injection = InjectionDetector.find(String.join("\n", pages));
        if (injection.isPresent()) {
            log.warn("Ingestão de '{}' recusada por injeção de prompt ({}).", filename, injection.get().pattern());
            throw new InvalidDocumentException("O arquivo '" + filename + "' foi recusado: o texto contém instrução "
                    + "dirigida a um sistema de IA (" + injection.get().pattern() + "), o que não é "
                    + "conteúdo de currículo. Trecho extraído do PDF: " + PyRepr.of(injection.get().excerpt())
                    + ". Esse texto pode estar invisível no arquivo "
                    + "(fonte branca ou tamanho zero), então confie no trecho acima, "
                    + "não no que aparece na tela ao abrir o PDF.");
        }

        return new PreparedResume(pages, built);
    }

    CandidateIdentity extract(String text) {
        if (text == null || text.isBlank()) {
            return CandidateIdentity.empty();
        }

        CandidateIdentity extracted = CandidateIdentity.empty();
        try {
            CandidateIdentity result = extractor.extract(text);
            if (result != null) {
                extracted = result;
            }
        } catch (RuntimeException ex) {
            log.warn("Extração via LLM falhou; caindo no fallback por regex.");
        }
        return extracted.completedFrom(text);
    }

    public static void requireIdentity(CandidateIdentity identity, String filename) {
        if (!identity.hasIdentity()) {
            throw new InvalidDocumentException("O arquivo '" + filename + "' foi recusado: não foi possível extrair "
                    + "nome, email ou telefone do currículo.");
        }
    }
}
