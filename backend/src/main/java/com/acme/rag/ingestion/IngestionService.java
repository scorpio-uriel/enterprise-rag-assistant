package com.acme.rag.ingestion;

import com.acme.rag.common.RagProperties;
import com.acme.rag.document.Document;
import com.acme.rag.document.DocumentRepository;
import com.acme.rag.document.FileStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Lecture → découpage → embeddings → pgvector. Volontairement sans {@code @Transactional} global :
 * chaque changement de statut est validé tout de suite, pour que {@code INDEXING} soit visible (et
 * bloque la suppression) pendant les appels à Ollama, qui peuvent durer.
 */
@Service
public class IngestionService {

  static final String DOCUMENT_ID = "documentId";
  public static final String FILE_NAME = "fileName";

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
  private static final int MAX_ERROR_LENGTH = 1000;

  private final DocumentRepository documentRepository;
  private final DocumentReaderFactory readerFactory;
  private final FileStorage fileStorage;
  private final VectorStore vectorStore;
  private final TextSplitter splitter;

  public IngestionService(
      DocumentRepository documentRepository,
      DocumentReaderFactory readerFactory,
      FileStorage fileStorage,
      VectorStore vectorStore,
      RagProperties properties) {
    this.documentRepository = documentRepository;
    this.readerFactory = readerFactory;
    this.fileStorage = fileStorage;
    this.vectorStore = vectorStore;
    this.splitter = new OverlappingTokenSplitter(properties.chunkSize(), properties.chunkOverlap());
  }

  public void ingest(UUID documentId) {
    if (documentRepository.startIndexing(documentId, Instant.now()) == 0) {
      log.info("Document {} supprimé ou déjà traité : indexation ignorée", documentId);
      return;
    }
    Document document = documentRepository.findById(documentId).orElseThrow();
    try {
      document.markIndexed(index(document));
      log.info("Document {} indexé ({} chunks)", documentId, document.getChunkCount());
    } catch (RuntimeException e) {
      log.warn("Indexation du document {} échouée", documentId, e);
      deleteVectorsQuietly(documentId); // aucun vecteur « fantôme » d'un import à moitié fait
      document.markFailed(errorMessage(e));
    }
    documentRepository.save(document);
  }

  /** Supprime tous les chunks d'un document (filtre sur la métadonnée {@code documentId}). */
  public void deleteVectors(UUID documentId) {
    vectorStore.delete(DOCUMENT_ID + " == '" + documentId + "'");
  }

  private int index(Document document) {
    List<org.springframework.ai.document.Document> pages =
        readerFactory.read(
            fileStorage.resolve(document.getStoragePath()), document.getContentType());
    List<org.springframework.ai.document.Document> chunks = splitter.apply(pages);
    if (chunks.isEmpty()) {
      throw new IllegalStateException("Aucun texte exploitable dans le document");
    }
    chunks.forEach(
        chunk -> {
          chunk.getMetadata().put(DOCUMENT_ID, document.getId().toString());
          chunk.getMetadata().put(FILE_NAME, document.getFileName());
        });
    vectorStore.add(chunks); // calcule les embeddings (Ollama) puis INSERT
    return chunks.size();
  }

  private void deleteVectorsQuietly(UUID documentId) {
    try {
      deleteVectors(documentId);
    } catch (RuntimeException e) {
      log.error("Nettoyage des vecteurs du document {} impossible", documentId, e);
    }
  }

  /** Toujours non vide (AC3.2), tronqué pour rester lisible dans l'interface. */
  private static String errorMessage(RuntimeException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }
}
