package com.acme.rag.chat;

import com.acme.rag.chat.dto.SourceDto;
import com.acme.rag.ingestion.DocumentReaderFactory;
import com.acme.rag.ingestion.IngestionService;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Construit le prompt système (règles + contexte numéroté) et les sources affichées à
 * l'utilisateur. Le prompt reçoit le texte complet des chunks ; seules les sources sont tronquées.
 */
@Component
public class RagPromptFactory {

  public static final String REFUSAL =
      "Je ne trouve pas cette information dans les documents disponibles.";

  static final int EXCERPT_MAX_LENGTH = 300; // AC6.1
  private static final String ELLIPSIS = "…";

  private static final String RULES =
      """
      Tu es l'assistant interne d'Acme. Réponds en français, de façon concise.
      Utilise UNIQUEMENT les extraits fournis ci-dessous. Si la réponse n'y figure pas,
      réponds exactement : « %s »
      Ne cite jamais de connaissances extérieures.

      Extraits :
      """
          .formatted(REFUSAL);

  public String systemPrompt(List<Document> chunks) {
    StringBuilder prompt = new StringBuilder(RULES);
    for (int i = 0; i < chunks.size(); i++) {
      Document chunk = chunks.get(i);
      prompt.append('[').append(i + 1).append("] (").append(fileName(chunk));
      Integer page = page(chunk);
      if (page != null) {
        prompt.append(", p. ").append(page);
      }
      prompt.append(") ").append(chunk.getText().strip()).append("\n\n");
    }
    return prompt.toString().strip();
  }

  public List<SourceDto> sources(List<Document> chunks) {
    return chunks.stream()
        .map(
            chunk ->
                new SourceDto(
                    fileName(chunk),
                    page(chunk),
                    truncate(chunk.getText()),
                    chunk.getScore() == null ? 0 : chunk.getScore()))
        .toList();
  }

  /** Au plus 300 caractères, ellipse comprise, sans couper un caractère hors BMP (emoji…). */
  static String truncate(String text) {
    String normalized = text == null ? "" : text.strip().replaceAll("\\s+", " ");
    if (normalized.length() <= EXCERPT_MAX_LENGTH) {
      return normalized;
    }
    int end = EXCERPT_MAX_LENGTH - ELLIPSIS.length();
    if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
      end--;
    }
    return normalized.substring(0, end).stripTrailing() + ELLIPSIS;
  }

  private static String fileName(Document chunk) {
    Object fileName = chunk.getMetadata().get(IngestionService.FILE_NAME);
    return fileName == null ? "document inconnu" : fileName.toString();
  }

  /** Relu depuis le JSON de pgvector, le numéro peut être un Integer ou un Long. */
  private static Integer page(Document chunk) {
    return chunk.getMetadata().get(DocumentReaderFactory.PAGE) instanceof Number page
        ? page.intValue()
        : null;
  }
}
