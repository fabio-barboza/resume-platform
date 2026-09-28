package dev.resumeplatform.resumeai.core.usecase.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.IngestionOutcome;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.core.domain.exception.ConflictException;
import dev.resumeplatform.resumeai.core.domain.exception.InvalidDocumentException;
import dev.resumeplatform.resumeai.core.gateway.ResumeGateway;
import dev.resumeplatform.resumeai.core.usecase.candidate.ReplaceCandidateUseCase;
import dev.resumeplatform.resumeai.infra.repository.CandidateRepository;
import dev.resumeplatform.resumeai.infra.repository.ChunkRepository;
import dev.resumeplatform.resumeai.infra.repository.DocumentRepository;
import dev.resumeplatform.resumeai.infra.entity.ChunkEntity;
import dev.resumeplatform.resumeai.support.DatabaseTest;
import dev.resumeplatform.resumeai.support.PdfFixtures;

class IngestionInvariantsTest extends DatabaseTest {
    @MockitoBean(name = "factualChatModel")
    OpenAiChatModel chatModel;
    @MockitoBean
    EmbeddingModel embeddingModel;
    @MockitoSpyBean
    ChunkRepository chunks;

    @Autowired
    IngestResumeUseCase ingestResume;
    @Autowired
    ReplaceResumeUseCase replaceResume;
    @Autowired
    DeleteResumeUseCase deleteResume;
    @Autowired
    GetResumeUseCase getResume;
    @Autowired
    ListResumesUseCase listResumes;
    @Autowired
    ResumeGateway resumes;
    @Autowired
    ReplaceCandidateUseCase replaceCandidate;
    @Autowired
    DocumentRepository documents;
    @Autowired
    CandidateRepository candidates;
    @Autowired
    TransactionTemplate transaction;

    private final List<Long> cleanup = new ArrayList<>();

    @BeforeEach
    void noNetwork() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("mock de teste: nenhum teste de invariante chama LLM"));
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[ChunkEntity.EMBEDDING_DIM]).toList();
        });
    }

    @AfterEach
    void removeCreatedDocuments() {
        for (Long documentId : cleanup) {
            documents.findRowById(documentId).ifPresent(row -> transaction.executeWithoutResult(tx -> {
                documents.deleteDocument(documentId);
                candidates.deleteIfOrphan(row.candidateId());
            }));
        }
    }

    private IngestionOutcome ingest(String filename, String text) {
        IngestionOutcome outcome = ingestResume.execute(filename, PdfFixtures.pdf(text));
        cleanup.add(outcome.documentId());
        return outcome;
    }

    private long chunkCountInDb(long documentId) {
        return chunks.countByDocument_Id(documentId);
    }

    @Test
    void postingSameFileTwiceIsNoopWithoutDuplicating() {
        byte[] content = PdfFixtures.pdf("Curriculo Fulano de Tal, telefone (11) 91234-5678");

        IngestionOutcome first = ingestResume.execute("cv.pdf", content);
        cleanup.add(first.documentId());
        IngestionOutcome second = ingestResume.execute("cv.pdf", content);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(resumes.inventory()).filteredOn(r -> r.id() == first.documentId()).hasSize(1);
        assertThat(chunkCountInDb(first.documentId())).isEqualTo(first.chunkCount());
    }

    @Test
    void putWithDifferentFileSwapsChunksKeepingDocumentId() {
        IngestionOutcome original = ingest("cv.pdf", "Conteudo original do curriculo, versao um, email um@example.com");

        IngestionOutcome replaced = replaceResume.execute(original.documentId(), "cv_v2.pdf", PdfFixtures.pdf(
                "Conteudo totalmente novo, versao dois, nada a ver com o primeiro, email dois@example.com"));

        assertThat(replaced.documentId()).isEqualTo(original.documentId());
        assertThat(replaced.duplicate()).isFalse();
        String texts = String.join(" ", chunks.findByCandidate(replaced.candidateId()).stream()
                .map(c -> c.content()).toList());
        assertThat(texts).contains("versao dois").doesNotContain("versao um");
        assertThat(getResume.execute(original.documentId()).filename()).isEqualTo("cv_v2.pdf");
    }

    @Test
    void putWithSameFileIsNoop() {
        byte[] content = PdfFixtures.pdf("Curriculo que nao vai mudar no PUT, email imutavel@example.com");
        IngestionOutcome original = ingestResume.execute("cv.pdf", content);
        cleanup.add(original.documentId());

        IngestionOutcome repeated = replaceResume.execute(original.documentId(), "cv.pdf", content);

        assertThat(repeated.duplicate()).isTrue();
        assertThat(repeated.chunkCount()).isEqualTo(original.chunkCount());
        assertThat(chunkCountInDb(original.documentId())).isEqualTo(original.chunkCount());
    }

    @Test
    void putWithAnotherDocumentsFileReturnsConflict() {
        IngestionOutcome docA = ingest("a.pdf", "Curriculo do candidato A, email a@example.com");

        byte[] fileB = PdfFixtures.pdf("Curriculo do candidato B, email b@example.com");
        cleanup.add(ingestResume.execute("b.pdf", fileB).documentId());

        assertThatThrownBy(() -> replaceResume.execute(docA.documentId(), "b.pdf", fileB))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("já pertence ao documento");
    }

    @Test
    void putFailingMidwayDoesNotLeaveDocumentWithoutChunks() {
        IngestionOutcome original = ingest("cv.pdf", "Curriculo integro antes da falha simulada, email integro@example.com");
        assertThat(original.chunkCount()).isPositive();

        doThrow(new IllegalStateException("falha simulada no meio da transação")).when(chunks).saveAll(anyList());

        assertThatThrownBy(() -> replaceResume.execute(original.documentId(), "cv2.pdf",
                PdfFixtures.pdf("Curriculo novo que nunca e gravado, email nunca@example.com")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(chunkCountInDb(original.documentId())).isEqualTo(original.chunkCount());
        assertThat(getResume.execute(original.documentId()).chunkCount()).isEqualTo(original.chunkCount());
    }

    @Test
    void deleteDocumentRemovesChunksByCascade() {
        IngestionOutcome doc = ingestResume.execute("cv.pdf",
                PdfFixtures.pdf("Curriculo que vai ser deletado, email deletado@example.com"));
        assertThat(chunkCountInDb(doc.documentId())).isPositive();

        deleteResume.execute(doc.documentId());

        assertThat(chunkCountInDb(doc.documentId())).isZero();
        assertThat(documents.findRowById(doc.documentId())).isEmpty();
    }

    @Test
    void deleteLeavingCandidateWithoutResumeRemovesCandidate() {
        IngestionOutcome doc = ingestResume.execute("cv.pdf",
                PdfFixtures.pdf("Curriculo unico deste candidato, email unico@example.com"));

        deleteResume.execute(doc.documentId());

        assertThat(candidates.findById(doc.candidateId())).isEmpty();
    }

    @Test
    void twoResumesWithSameEmailLinkToSameCandidate() {
        String email = "candidato.duplicado@example.com";
        IngestionOutcome first = ingest("cv1.pdf", "Curriculo A, contato " + email);
        IngestionOutcome second = ingest("cv2.pdf", "Curriculo B, bem diferente, email " + email);

        assertThat(first.documentId()).isNotEqualTo(second.documentId());
        assertThat(first.candidateId()).isEqualTo(second.candidateId());
    }

    @Test
    void candidatePutDoesNotChangeChunkCount() {
        IngestionOutcome doc = ingest("cv.pdf", "Curriculo de quem vai ter o cadastro editado, email editado@example.com");
        long before = chunkCountInDb(doc.documentId());
        assertThat(before).isPositive();

        replaceCandidate.execute(doc.candidateId(), "Nome Editado à Mão", "editado@example.com", "11999999999");

        assertThat(chunkCountInDb(doc.documentId())).isEqualTo(before);
    }

    @Test
    void candidatePutWithAnotherCandidatesEmailIsConflict() {
        ingest("x.pdf", "Curriculo X, email x@example.com");
        IngestionOutcome y = ingest("y.pdf", "Curriculo Y, email y@example.com");

        assertThatThrownBy(() -> replaceCandidate.execute(y.candidateId(), "Y", "x@example.com", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("já pertence a outro candidato");
    }

    @Test
    void pdfWithoutExtractableTextIsRefusedAndStoresNothing() {
        long before = listResumes.execute(100, 0).total();

        assertThatThrownBy(() -> ingestResume.execute("escaneado.pdf", PdfFixtures.withoutText()))
                .isInstanceOf(InvalidDocumentException.class);

        assertThat(listResumes.execute(100, 0).total()).isEqualTo(before);
    }

    @Test
    void regexFallbackIdentifiesCandidate() {
        IngestionOutcome withEmail = ingest("e.pdf", "Fulano, contato fulano@example.com");
        IngestionOutcome phoneOnly = ingest("p.pdf", "Ciclano, telefone (21) 99876-5432");

        assertThat(withEmail.status()).isEqualTo(ResumeStatus.INGESTED);
        assertThat(phoneOnly.status()).isEqualTo(ResumeStatus.PENDING_REVIEW);
        assertThat(candidates.findById(phoneOnly.candidateId()).orElseThrow().getPhone()).isEqualTo("(21) 99876-5432");
    }
}
