CREATE TABLE document (
    id            UUID PRIMARY KEY,
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL UNIQUE,   -- détection des doublons (AC2.5)
    storage_path  VARCHAR(500) NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','INDEXING','INDEXED','FAILED')),
    error_message TEXT,
    chunk_count   INT          NOT NULL DEFAULT 0,
    uploaded_by   UUID         NOT NULL REFERENCES app_user(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
