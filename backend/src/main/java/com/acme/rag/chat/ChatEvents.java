package com.acme.rag.chat;

import com.acme.rag.chat.dto.DoneEvent;
import com.acme.rag.chat.dto.ErrorEvent;
import com.acme.rag.chat.dto.SourceDto;
import com.acme.rag.chat.dto.TokenEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Fabriques des événements SSE de {@code POST /api/chat} : le contrat avec le front (PLAN § 2.7).
 * Spring MVC sérialise {@code data} en JSON et écrit {@code event:<nom>} puis {@code data:<json>}.
 */
public final class ChatEvents {

  public static final String TOKEN = "token";
  public static final String SOURCES = "sources";
  public static final String DONE = "done";
  public static final String ERROR = "error";

  private ChatEvents() {}

  public static ServerSentEvent<?> token(String text) {
    return event(TOKEN, new TokenEvent(text));
  }

  public static ServerSentEvent<?> sources(List<SourceDto> sources) {
    return event(SOURCES, sources);
  }

  public static ServerSentEvent<?> done(UUID conversationId, UUID messageId) {
    return event(DONE, new DoneEvent(conversationId, messageId));
  }

  public static ServerSentEvent<?> error(String message) {
    return event(ERROR, new ErrorEvent(message));
  }

  private static <T> ServerSentEvent<T> event(String name, T data) {
    return ServerSentEvent.builder(data).event(name).build();
  }
}
