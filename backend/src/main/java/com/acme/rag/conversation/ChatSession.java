package com.acme.rag.conversation;

import java.util.List;
import java.util.UUID;

/**
 * Ce que le chat doit savoir avant de lancer le flux : la conversation (déjà vérifiée), si elle
 * reste à créer, et l'historique à injecter dans le prompt, du plus ancien au plus récent.
 */
public record ChatSession(UUID conversationId, UUID userId, boolean isNew, List<Message> history) {}
