package com.acme.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class OverlappingTokenSplitterTest {

  // « w0 w1 w2 … » : des mots courts, faciles à repérer d'un chunk à l'autre
  private static final String TEXT =
      IntStream.range(0, 300).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));

  @Test
  void consecutiveChunksShareTheOverlap() {
    List<Document> chunks = new OverlappingTokenSplitter(50, 10).apply(List.of(new Document(TEXT)));

    assertThat(chunks).hasSizeGreaterThan(2);
    for (int i = 1; i < chunks.size(); i++) {
      String previous = chunks.get(i - 1).getText();
      String firstWord = chunks.get(i).getText().split(" ")[0];
      assertThat(previous).as("le chunk %d reprend la fin du précédent", i).contains(firstWord);
    }
    assertThat(chunks.getLast().getText()).endsWith("w299");
  }

  @Test
  void withoutOverlapChunksDoNotRepeatText() {
    List<Document> chunks = new OverlappingTokenSplitter(50, 0).apply(List.of(new Document(TEXT)));

    String rejoined =
        chunks.stream().map(Document::getText).collect(Collectors.joining(" ")).replace("  ", " ");
    assertThat(rejoined.split("w0 ")).hasSize(2); // w0 n'apparaît qu'une fois
  }

  @Test
  void shortTextGivesOneChunkAndKeepsMetadata() {
    List<Document> chunks =
        new OverlappingTokenSplitter(50, 10)
            .apply(List.of(new Document("Bonjour le monde", Map.of("page", 2))));

    assertThat(chunks)
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.getText()).isEqualTo("Bonjour le monde");
              assertThat(chunk.getMetadata()).containsEntry("page", 2);
            });
  }

  @Test
  void rejectsOverlapNotSmallerThanChunk() {
    assertThatThrownBy(() -> new OverlappingTokenSplitter(10, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
