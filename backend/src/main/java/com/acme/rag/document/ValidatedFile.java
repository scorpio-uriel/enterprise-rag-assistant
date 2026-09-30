package com.acme.rag.document;

/** Résultat de {@link FileValidator} : extension normalisée et type MIME détecté par Tika. */
public record ValidatedFile(String extension, String detectedType) {}
