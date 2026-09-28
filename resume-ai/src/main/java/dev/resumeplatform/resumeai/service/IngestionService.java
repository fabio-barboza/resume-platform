package dev.resumeplatform.resumeai.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.config.ResumeAiProperties;
import dev.resumeplatform.resumeai.db.Candidate;
import dev.resumeplatform.resumeai.db.CandidateRepository;
import dev.resumeplatform.resumeai.db.Chunk;
import dev.resumeplatform.resumeai.db.ChunkRepository;
import dev.resumeplatform.resumeai.db.Document;
import dev.resumeplatform.resumeai.db.DocumentRepository;
import dev.resumeplatform.resumeai.db.ResumeRow;
import dev.resumeplatform.resumeai.db.UniqueViolation;
import dev.resumeplatform.resumeai.guardrail.InjectionDetector;
import dev.resumeplatform.resumeai.infra.ResumeStorage;
import dev.resumeplatform.resumeai.pdf.PdfText;
import dev.resumeplatform.resumeai.pdf.TextChunk;

@Service
public class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public static final String STATUS_INGESTED = "ingested";
    public static final String STATUS_PENDING_REVIEW = "pending_review";

    private final CandidateRepository candidates;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ExtractionService extractionService;
    private final EmbeddingModel embeddingModel;
    private final ResumeStorage storage;
    private final TransactionTemplate transaction;
    private final ResumeAiProperties.Ingestion config;

    public IngestionService(CandidateRepository candidates, DocumentRepository documents, ChunkRepository chunks,
            ExtractionService extractionService, EmbeddingModel embeddingModel, ResumeStorage storage,
            TransactionTemplate transaction, ResumeAiProperties properties) {
        this.candidates = candidates;
        this.documents = documents;
        this.chunks = chunks;
        this.extractionService = extractionService;
        this.embeddingModel = embeddingModel;
        this.storage = storage;
        this.transaction = transaction;
        this.config = properties.ingestion();
    }

    record Prepared(List<String> pages, List<TextChunk> chunks) {
    }

    private static final class DuplicateFileException extends RuntimeException {
        DuplicateFileException(Throwable cause) {
            super(cause);
        }
    }

    public IngestionOutcome ingest(String filename, byte[] content) {
        String digest = PdfText.fileHash(content);

        var already = documents.findRowByFileHash(digest);
        if (already.isPresent()) {
            return duplicate(already.get());
        }

        Prepared prepared = prepare(content, filename);
        CandidateExtraction extracted = extractionService.extract(
                PdfText.firstPagesText(prepared.pages(), config.extractionPages()));
        requireIdentity(extracted, filename);
        List<float[]> embeddings = embed(prepared.chunks());
        String status = decideStatus(extracted);

        storage.store(filename, digest, content);
        try {
            return transaction.execute(tx -> {
                Candidate candidate = resolveCandidate(extracted, null);
                Document document;
                try {
                    document = documents.saveAndFlush(
                            new Document(candidate, filename, digest, prepared.pages().size(), status));
                } catch (DataIntegrityViolationException ex) {
                    if (UniqueViolation.isCause(ex)) {
                        throw new DuplicateFileException(ex);
                    }
                    throw ex;
                }
                long chunkCount = saveChunks(document, prepared.chunks(), embeddings);
                return new IngestionOutcome(document.getId(), filename, status, chunkCount, candidate.getId(), false);
            });
        } catch (DuplicateFileException ex) {
            storage.discard(filename, digest);
            return duplicate(documents.findRowByFileHash(digest).orElseThrow());
        } catch (RuntimeException ex) {
            storage.discard(filename, digest);
            throw ex;
        }
    }

    public IngestionOutcome replace(long documentId, String filename, byte[] content) {
        String digest = PdfText.fileHash(content);

        ResumeRow current = documents.findRowById(documentId)
                .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
        if (current.fileHash().equals(digest)) {
            return duplicate(current);
        }
        documents.findRowByFileHash(digest).ifPresent(clash -> {
            throw new ConflictException("O arquivo enviado já pertence ao documento " + clash.id() + ".");
        });

        Prepared prepared = prepare(content, filename);
        CandidateExtraction extracted = extractionService.extract(
                PdfText.firstPagesText(prepared.pages(), config.extractionPages()));
        requireIdentity(extracted, filename);
        List<float[]> embeddings = embed(prepared.chunks());
        String status = decideStatus(extracted);
        long previousCandidateId = current.candidateId();

        storage.store(filename, digest, content);
        IngestionOutcome outcome;
        try {
            outcome = transaction.execute(tx -> {
                Candidate candidate = resolveCandidate(extracted, previousCandidateId);
                chunks.deleteByDocumentId(documentId);
                Document document = documents.findById(documentId)
                        .orElseThrow(() -> new NotFoundException("Documento " + documentId + " não encontrado."));
                document.replaceFile(candidate, filename, digest, prepared.pages().size(), status);
                try {
                    documents.flush();
                } catch (DataIntegrityViolationException ex) {
                    if (UniqueViolation.isCause(ex)) {
                        throw new DuplicateFileException(ex);
                    }
                    throw ex;
                }
                documents.touchIngestedAt(documentId);
                long chunkCount = saveChunks(document, prepared.chunks(), embeddings);
                if (candidate.getId() != previousCandidateId) {
                    candidates.deleteIfOrphan(previousCandidateId);
                }
                return new IngestionOutcome(documentId, filename, status, chunkCount, candidate.getId(), false);
            });
        } catch (DuplicateFileException ex) {
            storage.discard(filename, digest);
            long clashId = documents.findRowByFileHash(digest).map(ResumeRow::id).orElse(-1L);
            throw new ConflictException("O arquivo enviado já pertence ao documento " + clashId + ".", ex);
        } catch (RuntimeException ex) {
            storage.discard(filename, digest);
            throw ex;
        }

        storage.discard(current.filename(), current.fileHash());
        return outcome;
    }

    Prepared prepare(byte[] content, String filename) {
        List<String> pages;
        try {
            pages = PdfText.readPages(content);
        } catch (IOException | RuntimeException ex) {
            throw new InvalidDocumentException("Não foi possível ler o PDF '" + filename + "': " + ex.getMessage(), ex);
        }

        if (pages.size() > config.maxResumePages()) {
            throw new InvalidDocumentException("O arquivo '" + filename + "' tem " + pages.size()
                    + " páginas; o limite é " + config.maxResumePages()
                    + ". Envie um currículo resumido ou aumente MAX_RESUME_PAGES.");
        }

        List<TextChunk> built = PdfText.buildChunks(pages);
        if (built.isEmpty()) {
            throw new InvalidDocumentException(
                    "O arquivo '" + filename + "' não tem texto extraível (PDF de imagem escaneada?).");
        }

        var injection = InjectionDetector.find(String.join("\n", pages));
        if (injection.isPresent()) {
            log.warn("Ingestão de '{}' recusada por injeção de prompt ({}).", filename, injection.get().pattern());
            throw new InvalidDocumentException("O arquivo '" + filename + "' foi recusado: o texto contém instrução "
                    + "dirigida a um sistema de IA (" + injection.get().pattern() + "), o que não é "
                    + "conteúdo de currículo. Trecho extraído do PDF: " + pyRepr(injection.get().excerpt())
                    + ". Esse texto pode estar invisível no arquivo "
                    + "(fonte branca ou tamanho zero), então confie no trecho acima, "
                    + "não no que aparece na tela ao abrir o PDF.");
        }

        return new Prepared(pages, built);
    }

    static void requireIdentity(CandidateExtraction extracted, String filename) {
        if (!extracted.hasIdentity()) {
            throw new InvalidDocumentException("O arquivo '" + filename + "' foi recusado: não foi possível extrair "
                    + "nome, email ou telefone do currículo.");
        }
    }

    private static String decideStatus(CandidateExtraction extracted) {
        return extracted.email() != null ? STATUS_INGESTED : STATUS_PENDING_REVIEW;
    }

    private List<float[]> embed(List<TextChunk> pieces) {
        return embeddingModel.embed(pieces.stream().map(TextChunk::content).toList());
    }

    private Candidate resolveCandidate(CandidateExtraction extracted, Long fallbackId) {
        Candidate existing = extracted.email() != null
                ? candidates.findFirstByEmailIgnoreCase(extracted.email()).orElse(null)
                : null;

        Candidate target = existing;
        if (target == null && fallbackId != null) {
            target = candidates.findById(fallbackId).orElse(null);
        }
        if (target == null) {
            return candidates.saveAndFlush(new Candidate(extracted.name(), extracted.email(), extracted.phone()));
        }

        target.replaceWith(extracted.name(), extracted.email(), extracted.phone());
        try {
            candidates.flush();
        } catch (DataIntegrityViolationException ex) {
            if (UniqueViolation.isCause(ex)) {
                throw new ConflictException("O email '" + extracted.email() + "' já pertence a outro candidato.", ex);
            }
            throw ex;
        }
        return target;
    }

    private long saveChunks(Document document, List<TextChunk> pieces, List<float[]> embeddings) {
        List<Chunk> rows = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            TextChunk piece = pieces.get(i);
            rows.add(new Chunk(piece.idFor(document.getId()), document, piece.page(), piece.chunkIndex(),
                    piece.content(), embeddings.get(i)));
        }
        chunks.saveAll(rows);
        chunks.flush();
        return rows.size();
    }

    private static IngestionOutcome duplicate(ResumeRow row) {
        return new IngestionOutcome(row.id(), row.filename(), row.status(), row.chunkCount(), row.candidateId(), true);
    }

    public static String pyRepr(String value) {
        String quote = value.contains("'") && !value.contains("\"") ? "\"" : "'";
        String escaped = value.replace("\\", "\\\\");
        if (quote.equals("'")) {
            escaped = escaped.replace("'", "\\'");
        }
        return quote + escaped + quote;
    }
}
