package com.acme.rag.chat.dto;

/** Données de l'événement SSE {@code error}, qui termine le flux à la place de {@code done}. */
public record ErrorEvent(String message) {}
