package com.acme.rag;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockReset;

/**
 * Remplace les beans d'IA dans les tests d'intégration. {@code @Primary} : le bean Ollama
 * auto-configuré existe toujours, mais c'est celui-ci qui est injecté (dans {@code PgVectorStore}
 * et dans {@code ChatClient.Builder} notamment). Le client Ollama ne contacte le serveur qu'à
 * l'usage, donc aucun appel réseau.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfig {

  @Bean
  @Primary
  EmbeddingModel fakeEmbeddingModel() {
    return new FakeEmbeddingModel();
  }

  /**
   * Mock Mockito du LLM : chaque test programme lui-même {@code call(...)}. Le contexte Spring
   * étant partagé entre les classes de test, {@code MockReset.AFTER} efface stubs et interactions
   * après chaque test (la réponse par défaut, elle, est conservée).
   */
  @Bean
  @Primary
  ChatModel mockChatModel() {
    return Mockito.mock(
        ChatModel.class,
        MockReset.withSettings(MockReset.AFTER).defaultAnswer(TestAiConfig::withDefaultOptions));
  }

  /**
   * {@code ChatClient} lit {@code getOptions()} au moment de l'appel : un mock nu renverrait {@code
   * null} (NullPointerException). On renvoie des options vides, le reste garde le comportement
   * Mockito habituel.
   */
  public static Object withDefaultOptions(InvocationOnMock invocation) throws Throwable {
    if (invocation.getMethod().getName().equals("getOptions")) {
      return ChatOptions.builder().build();
    }
    return Mockito.RETURNS_DEFAULTS.answer(invocation);
  }
}
