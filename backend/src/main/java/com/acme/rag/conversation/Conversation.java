package com.acme.rag.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Fil de discussion d'un utilisateur. L'id est fourni par le service : il est choisi dès la
 * question, mais la ligne n'est insérée qu'une fois la réponse obtenue. Le propriétaire est un
 * simple UUID (comme {@code Document.uploadedBy}) : pas besoin de charger l'entité {@code User}.
 */
@Entity
@Table(name = "conversation")
public class Conversation {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false, length = 120)
  private String title;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Conversation() {} // requis par JPA

  public Conversation(UUID id, UUID userId, String title, Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.title = title;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  /** Fait remonter la conversation en tête de liste ({@code ORDER BY updated_at DESC}). */
  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTitle() {
    return title;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
