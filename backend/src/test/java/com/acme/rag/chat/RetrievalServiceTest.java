package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.acme.rag.common.RagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.unit.DataSize;

class RetrievalServiceTest {

  @Test
  void searchUsesTopKAndThresholdFromConfiguration() { // AC8.2 : seuil lu depuis la config
    VectorStore vectorStore = mock(VectorStore.class);
    RagProperties properties =
        new RagProperties("unused", DataSize.ofMegabytes(20), 800, 100, 7, 0.42);

    new RetrievalService(vectorStore, properties).retrieve("Combien de jours de congés ?");

    // ArgumentCaptor récupère l'objet réellement passé au mock pour l'inspecter
    ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(request.capture());
    assertThat(request.getValue().getQuery()).isEqualTo("Combien de jours de congés ?");
    assertThat(request.getValue().getTopK()).isEqualTo(7);
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.42);
  }
}
