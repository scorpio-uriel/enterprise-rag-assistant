package com.acme.rag.ingestion;

import com.acme.rag.common.AsyncConfig;
import com.acme.rag.document.DocumentUploadedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Déclenche l'indexation d'un document importé. {@code AFTER_COMMIT} : l'écouteur ne s'exécute que
 * si la transaction de l'import a été validée, donc la ligne est visible par le thread
 * d'indexation. {@code @Async} : la requête HTTP a déjà répondu {@code 202}, l'indexation tourne
 * dans le pool.
 */
@Component
public class IngestionListener {

  private final IngestionService ingestionService;

  public IngestionListener(IngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  @Async(AsyncConfig.INGESTION_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDocumentUploaded(DocumentUploadedEvent event) {
    ingestionService.ingest(event.documentId());
  }
}
