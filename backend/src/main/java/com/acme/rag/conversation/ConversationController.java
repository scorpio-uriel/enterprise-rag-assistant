package com.acme.rag.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Conversations de l'utilisateur connecté (le sujet du JWT est son email). */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

  private final ConversationService conversationService;

  public ConversationController(ConversationService conversationService) {
    this.conversationService = conversationService;
  }

  @GetMapping
  public List<ConversationSummaryDto> list(@AuthenticationPrincipal Jwt jwt) {
    return conversationService.list(jwt.getSubject());
  }

  /** {@code 404} si la conversation n'existe pas ou appartient à un autre utilisateur (AC7.3). */
  @GetMapping("/{id}")
  public ConversationDetailDto get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return conversationService.get(id, jwt.getSubject());
  }
}
