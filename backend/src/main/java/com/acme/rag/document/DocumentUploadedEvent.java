package com.acme.rag.document;

import java.util.UUID;

/** Publié après un import réussi ; {@code IngestionListener} l'indexe après le commit. */
public record DocumentUploadedEvent(UUID documentId) {}
