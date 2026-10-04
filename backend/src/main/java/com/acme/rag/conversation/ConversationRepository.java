package com.acme.rag.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  /** Vide si la conversation n'existe pas ou appartient à quelqu'un d'autre : même 404 (AC7.3). */
  Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);

  /**
   * Projection DTO : le type de retour étant un record, Spring Data ne sélectionne que ses colonnes
   * et appelle son constructeur, sans charger d'entité.
   */
  List<ConversationSummaryDto> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
