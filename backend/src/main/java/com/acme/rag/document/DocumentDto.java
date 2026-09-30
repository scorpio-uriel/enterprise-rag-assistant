package com.acme.rag.document;

import java.time.Instant;
import java.util.UUID;

/** Contrat de l'API : on n'expose jamais l'entité (ni {@code storagePath}, ni {@code sha256}). */
public record DocumentDto(
    UUID id,
    String fileName,
    String contentType,
    long sizeBytes,
    DocumentStatus status,
    String errorMessage,
    int chunkCount,
    Instant createdAt) {

  static DocumentDto from(Document document) {
    return new DocumentDto(
        document.getId(),
        document.getFileName(),
        document.getContentType(),
        document.getSizeBytes(),
        document.getStatus(),
        document.getErrorMessage(),
        document.getChunkCount(),
        document.getCreatedAt());
  }
}
