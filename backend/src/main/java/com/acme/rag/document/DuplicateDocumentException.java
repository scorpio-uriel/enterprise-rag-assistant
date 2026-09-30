package com.acme.rag.document;

/** Un document au contenu identique (même SHA-256) existe déjà : 409 (AC2.5). */
public class DuplicateDocumentException extends RuntimeException {

  public DuplicateDocumentException() {
    super("Ce document a déjà été importé");
  }
}
