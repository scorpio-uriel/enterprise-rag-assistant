package com.acme.rag.chat.dto;

/**
 * Un extrait cité à l'appui d'une réponse (AC6.1). {@code page} est absent pour les formats sans
 * pagination (txt, md, docx) ; {@code excerpt} fait au plus 300 caractères.
 */
public record SourceDto(String fileName, Integer page, String excerpt, double score) {}
