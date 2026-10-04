package com.acme.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Corps de {@code POST /api/chat}. {@code conversationId} est facultatif : absent, une nouvelle
 * conversation est ouverte (simple UUID non persisté jusqu'au jalon J7).
 */
public record ChatRequest(UUID conversationId, @NotBlank @Size(max = 1000) String question) {}
