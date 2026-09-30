package com.acme.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Remplace les beans d'IA dans les tests d'intégration. {@code @Primary} : le bean Ollama
 * auto-configuré existe toujours, mais c'est celui-ci qui est injecté (dans {@code PgVectorStore}
 * notamment). Le client Ollama ne contacte le serveur qu'à l'usage, donc aucun appel réseau.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfig {

  @Bean
  @Primary
  EmbeddingModel fakeEmbeddingModel() {
    return new FakeEmbeddingModel();
  }
}
