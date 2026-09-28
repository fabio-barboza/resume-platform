package dev.resumeplatform.resumeai.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.service.CandidateService;
import dev.resumeplatform.resumeai.support.DatabaseTest;

class CandidateCountTest extends DatabaseTest {
    @Autowired
    CandidateRepository candidates;
    @Autowired
    DocumentRepository documents;
    @Autowired
    ChunkRepository chunks;
    @Autowired
    CandidateService candidateService;
    @Autowired
    TransactionTemplate transaction;

    private final List<Long[]> created = new java.util.ArrayList<>();

    private void candidateWithChunks(String name, String... contents) {
        transaction.executeWithoutResult(tx -> {
            Candidate candidate = candidates.saveAndFlush(new Candidate(name, null, null));
            Document document = documents.saveAndFlush(new Document(candidate, name + ".pdf", "hash-" + name, 1,
                    "ingested"));
            for (int i = 0; i < contents.length; i++) {
                chunks.save(new Chunk(document.getId() + "-p1-c" + i, document, 1, i, contents[i],
                        new float[Chunk.EMBEDDING_DIM]));
            }
            created.add(new Long[] {candidate.getId(), document.getId()});
        });
    }

    @BeforeEach
    void twoCandidates() {
        candidateWithChunks("Ana", "Experiência com JavaScript e React.", "Também usou javascript em projetos pessoais.");
        candidateWithChunks("Beto", "Backend em Python e Django.");
    }

    @AfterEach
    void cleanup() {
        transaction.executeWithoutResult(tx -> created.forEach(ids -> {
            documents.deleteDocument(ids[1]);
            candidates.deleteIfOrphan(ids[0]);
        }));
    }

    @Test
    void presentTermCountsRight() {
        assertThat(chunks.countCandidatesByTerms(List.of("Python"))).isEqualTo(Map.of("Python", 1L));
    }

    @Test
    void absentTermReturnsZero() {
        assertThat(chunks.countCandidatesByTerms(List.of("Cobol"))).isEqualTo(Map.of("Cobol", 0L));
    }

    @Test
    void accentAndCaseAreIgnored() {
        assertThat(chunks.countCandidatesByTerms(List.of("javascript"))).isEqualTo(Map.of("javascript", 1L));
    }

    @Test
    void twoChunksCountOneCandidate() {
        assertThat(chunks.countCandidatesByTerms(List.of("JavaScript")).get("JavaScript")).isEqualTo(1L);
    }

    @Test
    void percentInTermIsNotAWildcard() {
        assertThat(chunks.countCandidatesByTerms(List.of("Python%"))).isEqualTo(Map.of("Python%", 0L));
    }

    @Test
    void emptyListReturnsEmptyMap() {
        assertThat(chunks.countCandidatesByTerms(List.of())).isEmpty();
    }

    @Test
    void serviceNormalizesTerms() {
        var counts = candidateService.countBySkill(List.of(" Python ", "Python", "", "Cobol"));
        assertThat(counts.keySet()).containsExactly("Python", "Cobol");
        assertThat(counts).isEqualTo(Map.of("Python", 1L, "Cobol", 0L));
    }

    @Test
    void serviceEmptyListDoesNotHitTheDatabase() {
        assertThat(candidateService.countBySkill(List.of("", "   "))).isEmpty();
    }

    @Test
    void searchByNameToleratesTyping() {
        var found = candidateService.searchByName("BETO");
        assertThat(found).extracting(c -> c.name()).containsExactly("Beto");
        assertThat(found.getFirst().chunks()).extracting(CandidateChunkRow::content)
                .containsExactly("Backend em Python e Django.");
        assertThat(candidateService.searchByName("Fulano Inexistente")).isEmpty();
    }
}
