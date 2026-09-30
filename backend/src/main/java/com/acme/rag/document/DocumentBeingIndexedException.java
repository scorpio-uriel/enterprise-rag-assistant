package com.acme.rag.document;

/**
 * Suppression refusée pendant l'indexation, pour éviter une course avec l'écriture des vecteurs.
 */
public class DocumentBeingIndexedException extends RuntimeException {

  public DocumentBeingIndexedException() {
    super("Le document est en cours d'indexation, réessayez une fois celle-ci terminée");
  }
}
