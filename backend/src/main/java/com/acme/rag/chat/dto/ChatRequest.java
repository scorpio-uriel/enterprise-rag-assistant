package com.acme.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Corps de {@code POST /api/chat}. {@code conversationId} est facultatif : absent, une nouvelle
 * conversation est ouverte ; présent, elle doit appartenir à l'utilisateur (sinon 404).
 */
public record ChatRequest(UUID conversationId, @NotBlank @Size(max = 1000) String question) {}
