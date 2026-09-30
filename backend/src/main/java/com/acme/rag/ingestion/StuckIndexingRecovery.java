package com.acme.rag.ingestion;

import com.acme.rag.document.Document;
import com.acme.rag.document.DocumentRepository;
import com.acme.rag.document.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au démarrage, aucune indexation ne peut être en cours : un document resté {@code INDEXING} a été
 * interrompu par un arrêt de l'application. On le passe en {@code FAILED} (sans vecteurs partiels)
 * plutôt que de le laisser bloqué ; le réessai automatique est une amélioration post-MVP.
 */
@Component
public class StuckIndexingRecovery implements ApplicationRunner {

  static final String MESSAGE = "Indexation interrompue, réimporter le document";

  private static final Logger log = LoggerFactory.getLogger(StuckIndexingRecovery.class);

  private final DocumentRepository documentRepository;
  private final IngestionService ingestionService;

  public StuckIndexingRecovery(
      DocumentRepository documentRepository, IngestionService ingestionService) {
    this.documentRepository = documentRepository;
    this.ingestionService = ingestionService;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (Document document : documentRepository.findAllByStatus(DocumentStatus.INDEXING)) {
      log.warn("Document {} bloqué en INDEXING : passage en FAILED", document.getId());
      ingestionService.deleteVectors(document.getId());
      document.markFailed(MESSAGE);
      documentRepository.save(document);
    }
  }
}
