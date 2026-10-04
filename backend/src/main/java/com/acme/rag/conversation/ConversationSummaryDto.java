package com.acme.rag.conversation;

import java.time.Instant;
import java.util.UUID;

/** Élément de {@code GET /api/conversations}. */
public record ConversationSummaryDto(UUID id, String title, Instant updatedAt) {}
