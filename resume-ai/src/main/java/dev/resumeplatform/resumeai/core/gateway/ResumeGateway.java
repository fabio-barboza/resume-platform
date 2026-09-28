package dev.resumeplatform.resumeai.core.gateway;

import java.util.List;
import java.util.Optional;

import dev.resumeplatform.resumeai.core.domain.InventoryEntry;
import dev.resumeplatform.resumeai.core.domain.Resume;
import dev.resumeplatform.resumeai.core.domain.ResumeStatus;
import dev.resumeplatform.resumeai.core.domain.StoredFile;

public interface ResumeGateway {
    Optional<Resume> findById(long id);

    Optional<Resume> findByFileHash(String fileHash);

    long count();

    List<Resume> list(int limit, int offset);

    List<InventoryEntry> inventory();

    Optional<StoredFile> findLatestFileOf(long candidateId);

    /** Lança {@link dev.resumeplatform.resumeai.core.domain.exception.DuplicateFileException} se o hash já for de outro documento. */
    long create(long candidateId, String filename, String fileHash, int pages, ResumeStatus status);

    /**
     * Troca o arquivo preservando o id e renova {@code ingested_at}. Falso se o documento não existe; lança
     * {@link dev.resumeplatform.resumeai.core.domain.exception.DuplicateFileException} se o hash já for de outro documento.
     */
    boolean replaceFile(long id, long candidateId, String filename, String fileHash, int pages, ResumeStatus status);

    void delete(long id);
}
