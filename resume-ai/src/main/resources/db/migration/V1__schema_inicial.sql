CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS candidates (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    name       TEXT,
    email      TEXT,
    phone      TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
    CONSTRAINT candidates_pkey PRIMARY KEY (id),
    CONSTRAINT candidates_email_key UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS documents (
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    candidate_id BIGINT NOT NULL,
    filename     TEXT NOT NULL,
    file_hash    TEXT NOT NULL,
    pages        INTEGER NOT NULL,
    status       TEXT NOT NULL,
    ingested_at  TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
    CONSTRAINT documents_pkey PRIMARY KEY (id),
    CONSTRAINT documents_file_hash_key UNIQUE (file_hash),
    CONSTRAINT documents_candidate_id_fkey FOREIGN KEY (candidate_id)
        REFERENCES candidates (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS documents_candidate_id_idx ON documents (candidate_id);

CREATE TABLE IF NOT EXISTS chunks (
    id          TEXT NOT NULL,
    document_id BIGINT NOT NULL,
    page        INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1024) NOT NULL,
    CONSTRAINT chunks_pkey PRIMARY KEY (id),
    CONSTRAINT chunks_document_id_page_chunk_index_key UNIQUE (document_id, page, chunk_index),
    CONSTRAINT chunks_document_id_fkey FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw_idx
    ON chunks USING hnsw (embedding vector_cosine_ops);
