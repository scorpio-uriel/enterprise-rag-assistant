package com.acme.rag.chat;

import com.acme.rag.chat.dto.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Point d'entrée du chat. Spring MVC sait renvoyer un {@code Flux} : il le transforme en flux SSE
 * et écrit chaque événement dès qu'il est produit, la requête se terminant dans un dispatch {@code
 * ASYNC} (d'où la règle correspondante dans {@code SecurityConfig}).
 */
@RestController
public class ChatController {

  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<?>> chat(
      @Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
    return chatService.ask(request, jwt.getSubject()); // le sujet du JWT est l'email
  }
}
