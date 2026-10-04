package com.acme.rag.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code ConversationId} se lit {@code conversation.id} : Spring Data suit la relation. */
public interface MessageRepository extends JpaRepository<Message, UUID> {

  /** Les 6 derniers messages, du plus récent au plus ancien : à inverser avant le LLM. */
  List<Message> findTop6ByConversationIdOrderByCreatedAtDesc(UUID conversationId);

  List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
