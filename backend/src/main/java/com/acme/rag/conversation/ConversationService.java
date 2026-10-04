package com.acme.rag.conversation;

import com.acme.rag.auth.UserRepository;
import com.acme.rag.chat.dto.SourceDto;
import com.acme.rag.common.NotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversations privées : chaque lecture vérifie le propriétaire. Une conversation nouvelle n'est
 * insérée qu'avec son premier échange complet, pour ne pas laisser de conversation vide si le LLM
 * échoue.
 */
@Service
public class ConversationService {

  static final int TITLE_MAX_LENGTH = 60;
  private static final String NOT_FOUND = "Conversation introuvable";

  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;
  private final UserRepository userRepository;

  public ConversationService(
      ConversationRepository conversationRepository,
      MessageRepository messageRepository,
      UserRepository userRepository) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.userRepository = userRepository;
  }

  /**
   * Prépare un échange : sans id, une nouvelle conversation (pas encore insérée) ; avec un id,
   * celle de l'utilisateur et son historique, ou {@link NotFoundException}.
   */
  @Transactional(readOnly = true)
  public ChatSession open(UUID conversationId, String email) {
    UUID userId = userId(email);
    if (conversationId == null) {
      return new ChatSession(UUID.randomUUID(), userId, true, List.of());
    }
    Conversation conversation = owned(conversationId, userId);
    return new ChatSession(conversation.getId(), userId, false, lastMessages(conversationId));
  }

  /**
   * Les derniers messages dans l'ordre chronologique. La requête les renvoie du plus récent au plus
   * ancien (c'est ce qui permet de garder les 6 derniers) : sans l'inversion, le LLM lirait le
   * dialogue à l'envers. Copie préalable car la liste de Spring Data peut être immuable.
   */
  List<Message> lastMessages(UUID conversationId) {
    List<Message> messages =
        new ArrayList<>(
            messageRepository.findTop6ByConversationIdOrderByCreatedAtDesc(conversationId));
    Collections.reverse(messages);
    return messages;
  }

  /**
   * Enregistre la question et la réponse (refus compris) en une transaction, en créant la
   * conversation si besoin.
   *
   * @return l'id du message de l'assistant, renvoyé dans l'événement {@code done}
   */
  @Transactional
  public UUID recordExchange(
      ChatSession session,
      String question,
      Instant askedAt,
      String answer,
      List<SourceDto> sources) {
    // PostgreSQL stocke à la microseconde : on tronque nous-mêmes, et la réponse est postérieure
    // d'au moins 1 µs à la question, sinon leur ordre serait indéterminé.
    Instant questionAt = askedAt.truncatedTo(ChronoUnit.MICROS);
    Instant answeredAt =
        max(Instant.now().truncatedTo(ChronoUnit.MICROS), questionAt.plus(1, ChronoUnit.MICROS));

    Conversation conversation;
    if (session.isNew()) {
      conversation =
          conversationRepository.save(
              new Conversation(
                  session.conversationId(), session.userId(), title(question), questionAt));
    } else {
      conversation = owned(session.conversationId(), session.userId());
    }
    conversation.touch(answeredAt); // entité gérée : mise à jour au commit

    messageRepository.save(new Message(conversation, MessageRole.USER, question, null, questionAt));
    Message reply =
        messageRepository.save(
            new Message(conversation, MessageRole.ASSISTANT, answer, sources, answeredAt));
    return reply.getId();
  }

  @Transactional(readOnly = true)
  public List<ConversationSummaryDto> list(String email) {
    return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId(email));
  }

  @Transactional(readOnly = true)
  public ConversationDetailDto get(UUID id, String email) {
    Conversation conversation = owned(id, userId(email));
    List<MessageDto> messages =
        messageRepository.findByConversationIdOrderByCreatedAtAsc(id).stream()
            .map(MessageDto::from)
            .toList();
    return new ConversationDetailDto(conversation.getId(), conversation.getTitle(), messages);
  }

  /** Les 60 premiers caractères, espaces normalisés, sans couper un caractère hors BMP (emoji…). */
  static String title(String question) {
    String normalized = question.strip().replaceAll("\\s+", " ");
    if (normalized.length() <= TITLE_MAX_LENGTH) {
      return normalized;
    }
    int end = TITLE_MAX_LENGTH;
    if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
      end--;
    }
    return normalized.substring(0, end).stripTrailing();
  }

  private Conversation owned(UUID id, UUID userId) {
    return conversationRepository
        .findByIdAndUserId(id, userId)
        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
  }

  private UUID userId(String email) {
    return userRepository
        .findByEmail(email)
        .orElseThrow(() -> new IllegalStateException("Utilisateur inconnu : " + email))
        .getId();
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
