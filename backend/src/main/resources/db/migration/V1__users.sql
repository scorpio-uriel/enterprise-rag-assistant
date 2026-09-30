CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt, jamais le mot de passe
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','USER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
