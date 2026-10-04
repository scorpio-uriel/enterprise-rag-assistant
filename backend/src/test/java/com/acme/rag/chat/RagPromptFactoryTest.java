package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.chat.dto.SourceDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagPromptFactoryTest {

  private final RagPromptFactory factory = new RagPromptFactory();

  @Test
  void systemPromptRequiresFrenchAndOnlyTheExcerpts() { // AC5.2
    String prompt = factory.systemPrompt(List.of(chunk("Texte", "a.pdf", 1, 0.9)));

    assertThat(prompt)
        .contains("en français")
        .contains("UNIQUEMENT les extraits")
        .contains(RagPromptFactory.REFUSAL);
  }

  @Test
  void contextIsNumberedWithFileNameAndPageWhenKnown() {
    String prompt =
        factory.systemPrompt(
            List.of(
                chunk("Deux jours de télétravail.", "teletravail.pdf", 2, 0.8),
                chunk("25 jours de congés.", "conges.md", null, 0.7)));

    assertThat(prompt)
        .contains("[1] (teletravail.pdf, p. 2) Deux jours de télétravail.")
        .contains("[2] (conges.md) 25 jours de congés.");
  }

  @Test
  void sourcesCarryFileNamePageExcerptAndScore() {
    List<SourceDto> sources = factory.sources(List.of(chunk("Texte", "a.pdf", 3, 0.42)));

    assertThat(sources).containsExactly(new SourceDto("a.pdf", 3, "Texte", 0.42));
  }

  @Test
  void pageStoredAsLongIsStillRead() { // le JSON relu depuis pgvector peut donner un Long
    Document chunk = new Document("Texte", Map.of("fileName", "a.pdf", "page", 7L));

    assertThat(factory.sources(List.of(chunk)).getFirst().page()).isEqualTo(7);
  }

  @Test
  void sourceExcerptsAreTruncatedTo300Characters() { // AC6.1
    String excerpt =
        factory.sources(List.of(chunk("mot ".repeat(250), "a.pdf", 1, 0.9))).getFirst().excerpt();

    assertThat(excerpt).hasSizeLessThanOrEqualTo(300).endsWith("…");
  }

  @Test
  void shortTextIsKeptWithNormalizedWhitespace() {
    assertThat(RagPromptFactory.truncate("  Horaires :\n 8h -  18h  "))
        .isEqualTo("Horaires : 8h - 18h");
  }

  @Test
  void truncationNeverSplitsASurrogatePair() {
    String text = "a".repeat(298) + "😀" + "b".repeat(10); // l'emoji occupe les index 298-299

    String truncated = RagPromptFactory.truncate(text);

    assertThat(truncated).hasSizeLessThanOrEqualTo(300).isEqualTo("a".repeat(298) + "…");
  }

  private static Document chunk(String text, String fileName, Integer page, double score) {
    Map<String, Object> metadata =
        page == null ? Map.of("fileName", fileName) : Map.of("fileName", fileName, "page", page);
    return Document.builder().text(text).metadata(metadata).score(score).build();
  }
}
