package dev.resumeplatform.resumeai.infra.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "documents")
public class DocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private CandidateEntity candidate;

    @Column(nullable = false)
    private String filename;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(nullable = false)
    private int pages;

    @Column(nullable = false)
    private String status;

    @Generated
    @Column(name = "ingested_at", insertable = false, updatable = false)
    private OffsetDateTime ingestedAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(CandidateEntity candidate, String filename, String fileHash, int pages, String status) {
        this.candidate = candidate;
        this.filename = filename;
        this.fileHash = fileHash;
        this.pages = pages;
        this.status = status;
    }

    public void replaceFile(CandidateEntity candidate, String filename, String fileHash, int pages, String status) {
        this.candidate = candidate;
        this.filename = filename;
        this.fileHash = fileHash;
        this.pages = pages;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public CandidateEntity getCandidate() {
        return candidate;
    }

    public String getFilename() {
        return filename;
    }

    public String getFileHash() {
        return fileHash;
    }

    public int getPages() {
        return pages;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getIngestedAt() {
        return ingestedAt;
    }
}
