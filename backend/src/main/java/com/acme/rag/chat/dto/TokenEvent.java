package com.acme.rag.chat.dto;

/** Données de l'événement SSE {@code token} : un fragment de la réponse en cours de génération. */
public record TokenEvent(String text) {}
