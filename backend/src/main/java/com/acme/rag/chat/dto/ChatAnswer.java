package com.acme.rag.chat.dto;

import com.acme.rag.chat.RagPromptFactory;
import java.util.List;

/** Réponse complète (non streaming) : le texte et les extraits qui l'appuient. */
public record ChatAnswer(String answer, List<SourceDto> sources) {

  /** Refus hors corpus (F8) : message exact, et aucune source. */
  public static ChatAnswer refusal() {
    return new ChatAnswer(RagPromptFactory.REFUSAL, List.of());
  }
}
