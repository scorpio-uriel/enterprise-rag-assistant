-- Table utilisée par PgVectorStore (Spring AI 2.0), créée ici plutôt que par Spring AI
-- (initialize-schema: false) pour rester versionnée par Flyway.
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY,          -- généré côté Java par Spring AI
    content   TEXT,
    metadata  JSONB,                     -- { documentId, fileName, page? }
    embedding VECTOR(768)                -- dimension de nomic-embed-text
);
CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
-- Suppression et comptage des vecteurs d'un document (AC4.2)
CREATE INDEX vector_store_document_idx  ON vector_store ((metadata->>'documentId'));
