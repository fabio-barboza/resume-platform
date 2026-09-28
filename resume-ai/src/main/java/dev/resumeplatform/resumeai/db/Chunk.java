package dev.resumeplatform.resumeai.db;

import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "chunks")
public class Chunk {
    public static final int EMBEDDING_DIM = 1024;

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private int page;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIM)
    @Column(nullable = false)
    private float[] embedding;

    protected Chunk() {
    }

    public Chunk(String id, Document document, int page, int chunkIndex, String content, float[] embedding) {
        this.id = id;
        this.document = document;
        this.page = page;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getPage() {
        return page;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
