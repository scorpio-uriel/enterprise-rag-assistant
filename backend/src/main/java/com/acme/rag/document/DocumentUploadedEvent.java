package com.acme.rag.document;

import java.util.UUID;

/** Publié après un import réussi ; l'indexation (J4) l'écoutera après le commit. */
public record DocumentUploadedEvent(UUID documentId) {}
