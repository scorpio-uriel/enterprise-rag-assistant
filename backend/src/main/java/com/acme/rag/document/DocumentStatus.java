package com.acme.rag.document;

/** Cycle de vie d'un document. Stocké en texte ({@code EnumType.STRING}), jamais en ordinal. */
public enum DocumentStatus {
  PENDING,
  INDEXING,
  INDEXED,
  FAILED
}
