package com.acme.rag.document;

/** Fichier refusé (vide, trop gros, extension ou type réel non autorisé) : 400. */
public class InvalidFileException extends RuntimeException {

  public InvalidFileException(String message) {
    super(message);
  }
}
