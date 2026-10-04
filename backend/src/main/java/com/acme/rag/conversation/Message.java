package com.acme.rag.conversation;

import com.acme.rag.chat.dto.SourceDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un message d'une conversation. {@code LAZY} : charger un message ne charge pas sa conversation
 * (une requête de moins) tant qu'on n'appelle pas {@link #getConversation()}. Les sources sont
 * sérialisées en JSON dans la colonne {@code jsonb} par Hibernate.
 */
@Entity
@Table(name = "message")
public class Message {

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
  private Conversation conversation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MessageRole role;

  @Column(nullable = false)
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<SourceDto> sources; // null pour une question ou un refus

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Message() {} // requis par JPA

  public Message(
      Conversation conversation,
      MessageRole role,
      String content,
      List<SourceDto> sources,
      Instant createdAt) {
    this.id = UUID.randomUUID(); // connu avant l'insertion : renvoyé dans l'événement done
    this.conversation = conversation;
    this.role = role;
    this.content = content;
    this.sources = sources;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public Conversation getConversation() {
    return conversation;
  }

  public MessageRole getRole() {
    return role;
  }

  public String getContent() {
    return content;
  }

  public List<SourceDto> getSources() {
    return sources;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
