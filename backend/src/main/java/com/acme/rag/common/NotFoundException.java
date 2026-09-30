package com.acme.rag.common;

/** Ressource introuvable, traduite en 404 par {@link GlobalExceptionHandler}. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
