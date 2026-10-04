package com.acme.rag.chat.dto;

import java.util.UUID;

/**
 * Données de l'événement SSE {@code done}, toujours le dernier d'un flux réussi. Jusqu'au jalon J7,
 * {@code messageId} est un UUID aléatoire qui fige seulement le contrat avec le front.
 */
public record DoneEvent(UUID conversationId, UUID messageId) {}
