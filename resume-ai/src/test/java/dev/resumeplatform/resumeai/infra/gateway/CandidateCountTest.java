package dev.resumeplatform.resumeai.infra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import dev.resumeplatform.resumeai.core.domain.CandidateChunk;
import dev.resumeplatform.resumeai.core.usecase.candidate.CountCandidatesBySkillUseCase;
import dev.resumeplatform.resumeai.core.usecase.candidate.SearchCandidatesByNameUseCase;
import dev.resumeplatform.resumeai.infra.repository.CandidateRepository;
import dev.resumeplatform.resumeai.infra.repository.ChunkRepository;
import dev.resumeplatform.resumeai.infra.repository.DocumentRepository;
import dev.resumeplatform.resumeai.infra.repository.entity.CandidateEntity;
import dev.resumeplatform.resumeai.infra.repository.entity.ChunkEntity;
import dev.resumeplatform.resumeai.infra.repository.entity.DocumentEntity;
import dev.resumeplatform.resumeai.support.DatabaseTest;

class CandidateCountTest extends DatabaseTest {
    @Autowired
    CandidateRepository candidates;
    @Autowired
    DocumentRepository documents;
    @Autowired
    ChunkRepository chunks;
    @Autowired
    CountCandidatesBySkillUseCase countBySkill;
    @Autowired
    SearchCandidatesByNameUseCase searchByName;
    @Autowired
    TransactionTemplate transaction;

    private final List<Long[]> created = new java.util.ArrayList<>();

    private void candidateWithChunks(String name, String... contents) {
        transaction.executeWithoutResult(tx -> {
            CandidateEntity candidate = candidates.saveAndFlush(new CandidateEntity(name, null, null));
            DocumentEntity document = documents.saveAndFlush(new DocumentEntity(candidate, name + ".pdf", "hash-" + name, 1,
                    "ingested"));
            for (int i = 0; i < contents.length; i++) {
                chunks.save(new ChunkEntity(document.getId() + "-p1-c" + i, document, 1, i, contents[i],
                        new float[ChunkEntity.EMBEDDING_DIM]));
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
        var counts = countBySkill.execute(List.of(" Python ", "Python", "", "Cobol"));
        assertThat(counts.keySet()).containsExactly("Python", "Cobol");
        assertThat(counts).isEqualTo(Map.of("Python", 1L, "Cobol", 0L));
    }

    @Test
    void serviceEmptyListDoesNotHitTheDatabase() {
        assertThat(countBySkill.execute(List.of("", "   "))).isEmpty();
    }

    @Test
    void searchByNameToleratesTyping() {
        var found = searchByName.execute("BETO");
        assertThat(found).extracting(c -> c.name()).containsExactly("Beto");
        assertThat(found.getFirst().chunks()).extracting(CandidateChunk::content)
                .containsExactly("Backend em Python e Django.");
        assertThat(searchByName.execute("Fulano Inexistente")).isEmpty();
    }
}
