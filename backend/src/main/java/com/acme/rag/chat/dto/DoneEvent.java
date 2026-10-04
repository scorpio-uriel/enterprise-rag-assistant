package com.acme.rag.chat.dto;

import java.util.UUID;

/**
 * Données de l'événement SSE {@code done}, toujours le dernier d'un flux réussi, émis une fois
 * l'échange enregistré. {@code messageId} est l'id du message de l'assistant.
 */
public record DoneEvent(UUID conversationId, UUID messageId) {}
