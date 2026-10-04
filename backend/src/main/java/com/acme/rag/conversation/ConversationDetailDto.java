package com.acme.rag.conversation;

import java.util.List;
import java.util.UUID;

/** Réponse de {@code GET /api/conversations/{id}} : messages dans l'ordre chronologique. */
public record ConversationDetailDto(UUID id, String title, List<MessageDto> messages) {}
