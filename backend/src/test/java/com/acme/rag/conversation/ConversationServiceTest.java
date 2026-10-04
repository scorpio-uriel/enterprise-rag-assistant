package com.acme.rag.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.rag.auth.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {

  private final MessageRepository messageRepository = mock(MessageRepository.class);
  private final ConversationService service =
      new ConversationService(
          mock(ConversationRepository.class), messageRepository, mock(UserRepository.class));

  @Test
  void shortQuestionIsTheTitle() {
    assertThat(ConversationService.title("  Combien de jours\nde télétravail ?  "))
        .isEqualTo("Combien de jours de télétravail ?");
  }

  @Test
  void longQuestionIsCutAt60Characters() {
    assertThat(ConversationService.title("a".repeat(100))).hasSize(60);
  }

  @Test
  void titleNeverSplitsAnEmoji() {
    String title = ConversationService.title("a".repeat(59) + "😀 suite");

    assertThat(title).isEqualTo("a".repeat(59));
  }

  /** Le piège du PLAN : la requête renvoie du plus récent au plus ancien. */
  @Test
  void lastMessagesAreReturnedInChronologicalOrder() {
    UUID conversationId = UUID.randomUUID();
    Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
    Message q1 = new Message(null, MessageRole.USER, "Q1", null, t0);
    Message r1 = new Message(null, MessageRole.ASSISTANT, "R1", null, t0.plusSeconds(1));
    Message q2 = new Message(null, MessageRole.USER, "Q2", null, t0.plusSeconds(2));
    when(messageRepository.findTop6ByConversationIdOrderByCreatedAtDesc(conversationId))
        .thenReturn(List.of(q2, r1, q1)); // liste immuable, comme peut l'être celle de Spring Data

    assertThat(service.lastMessages(conversationId)).containsExactly(q1, r1, q2);
  }
}
