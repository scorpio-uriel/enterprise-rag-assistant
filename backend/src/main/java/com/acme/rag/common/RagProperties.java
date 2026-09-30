package com.acme.rag.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Réglages métier du RAG (préfixe {@code rag}). {@code DataSize} convertit « 20MB » en octets. Les
 * réglages de recherche (topK, seuil…) s'ajouteront aux jalons suivants.
 */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(@NotBlank String storageDir, @NotNull DataSize maxFileSize) {}
