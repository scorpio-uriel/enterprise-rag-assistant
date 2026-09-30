package com.acme.rag.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Document importé. L'id est fourni par le service (et non généré par JPA) : il sert aussi de nom
 * au fichier sur disque, qui doit être écrit avant l'insertion.
 */
@Entity
@Table(name = "document")
public class Document {

  @Id private UUID id;

  @Column(name = "file_name", nullable = false)
  private String fileName;

  @Column(name = "content_type", nullable = false, length = 100)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @JdbcTypeCode(SqlTypes.CHAR) // colonne CHAR(64) : sans cela, validate attend un VARCHAR
  @Column(nullable = false, unique = true, length = 64)
  private String sha256;

  @Column(name = "storage_path", nullable = false, length = 500)
  private String storagePath;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private DocumentStatus status;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  @Column(name = "uploaded_by", nullable = false, updatable = false)
  private UUID uploadedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Document() {} // requis par JPA

  public Document(
      UUID id,
      String fileName,
      String contentType,
      long sizeBytes,
      String sha256,
      String storagePath,
      UUID uploadedBy) {
    this.id = id;
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    this.storagePath = storagePath;
    this.uploadedBy = uploadedBy;
    this.status = DocumentStatus.PENDING;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void markIndexed(int chunkCount) {
    this.chunkCount = chunkCount;
    this.errorMessage = null;
    changeStatus(DocumentStatus.INDEXED);
  }

  public void markFailed(String errorMessage) {
    this.chunkCount = 0;
    this.errorMessage = errorMessage;
    changeStatus(DocumentStatus.FAILED);
  }

  private void changeStatus(DocumentStatus status) {
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getFileName() {
    return fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
