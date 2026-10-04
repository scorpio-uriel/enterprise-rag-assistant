package com.acme.rag.common;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Réglages métier du RAG (préfixe {@code rag}). {@code DataSize} convertit « 20MB » en octets.
 * {@code chunkSize} et {@code chunkOverlap} sont exprimés en tokens. {@code topK} borne le nombre
 * d'extraits retrouvés ; {@code similarityThreshold} est le score (cosinus) minimal en dessous
 * duquel un extrait est ignoré.
 */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    @NotBlank String storageDir,
    @NotNull DataSize maxFileSize,
    @Positive int chunkSize,
    @PositiveOrZero int chunkOverlap,
    @Min(1) @Max(20) int topK,
    @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold) {

  /** Sinon le découpage n'avancerait jamais : chaque chunk doit apporter des tokens nouveaux. */
  @AssertTrue(message = "rag.chunk-overlap doit être inférieur à rag.chunk-size")
  public boolean isOverlapSmallerThanChunk() {
    return chunkOverlap < chunkSize;
  }
}
