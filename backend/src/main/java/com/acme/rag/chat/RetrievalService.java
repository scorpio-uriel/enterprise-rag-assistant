package com.acme.rag.chat;

import com.acme.rag.common.RagProperties;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Recherche vectorielle explicite, avant tout appel au LLM : c'est ce qui permet de refuser sans
 * l'appeler (AC8.1). Le seuil est appliqué par pgvector lui-même ; le score renvoyé vaut 1 − la
 * distance cosinus.
 */
@Service
public class RetrievalService {

  private final VectorStore vectorStore;
  private final RagProperties properties;

  public RetrievalService(VectorStore vectorStore, RagProperties properties) {
    this.vectorStore = vectorStore;
    this.properties = properties;
  }

  /** Les {@code topK} chunks les plus proches dont le score atteint le seuil ; vide sinon. */
  public List<Document> retrieve(String question) {
    return vectorStore.similaritySearch(
        SearchRequest.builder()
            .query(question)
            .topK(properties.topK())
            .similarityThreshold(properties.similarityThreshold())
            .build());
  }
}
