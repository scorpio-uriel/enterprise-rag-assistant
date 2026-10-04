CREATE TABLE conversation (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user(id),  -- propriétaire (AC7.3)
    title      VARCHAR(120) NOT NULL,                          -- 60 premiers caractères de la 1re question
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX conversation_user_idx ON conversation (user_id, updated_at DESC);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content         TEXT        NOT NULL,
    sources         JSONB,                        -- citations affichées après rechargement
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX message_conversation_idx ON message (conversation_id, created_at);
