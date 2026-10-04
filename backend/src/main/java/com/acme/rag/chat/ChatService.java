package com.acme.rag.chat;

import com.acme.rag.chat.dto.ChatRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orchestre recherche → décision de refus → appel au LLM, sous forme de flux d'événements SSE :
 * {@code token}…, {@code sources}, {@code done} ; ou {@code token} (refus) puis {@code done} ; ou
 * {@code error} en cas d'échec. Volontairement sans {@code @Transactional} : la transaction se
 * fermerait avant que le flux ne produise quoi que ce soit.
 */
@Service
public class ChatService {

  static final String MODEL_ERROR = "Le modèle ne répond pas";

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final RetrievalService retrievalService;
  private final RagPromptFactory promptFactory;
  private final ChatClient chatClient;

  /** {@code ChatClient.Builder} est auto-configuré par Spring AI autour du {@code ChatModel}. */
  public ChatService(
      RetrievalService retrievalService,
      RagPromptFactory promptFactory,
      ChatClient.Builder chatClientBuilder) {
    this.retrievalService = retrievalService;
    this.promptFactory = promptFactory;
    this.chatClient = chatClientBuilder.build();
  }

  public Flux<ServerSentEvent<?>> ask(ChatRequest request) {
    UUID conversationId =
        request.conversationId() != null ? request.conversationId() : UUID.randomUUID();
    // defer : la recherche ne s'exécute qu'à l'abonnement, et ses exceptions deviennent des
    // erreurs du flux, rattrapées par onErrorResume comme celles du LLM.
    return Flux.defer(() -> answerEvents(request.question()))
        .concatWith(Mono.fromSupplier(() -> ChatEvents.done(conversationId, UUID.randomUUID())))
        .onErrorResume(
            e -> {
              log.error("Échec de la génération de la réponse", e);
              return Flux.just(ChatEvents.error(MODEL_ERROR));
            });
  }

  private Flux<ServerSentEvent<?>> answerEvents(String question) {
    List<Document> chunks = retrievalService.retrieve(question);
    if (chunks.isEmpty()) {
      return Flux.just(ChatEvents.token(RagPromptFactory.REFUSAL)); // LLM jamais appelé (AC8.1)
    }
    return chatClient
        .prompt()
        // SystemMessage déjà construit : .system(String) passerait le texte au moteur de
        // template, qui échouerait sur les accolades présentes dans un extrait.
        .messages(new SystemMessage(promptFactory.systemPrompt(chunks)))
        .user(question)
        .stream()
        .content()
        .filter(text -> !text.isEmpty()) // Ollama termine par un fragment vide (done=true)
        .<ServerSentEvent<?>>map(ChatEvents::token)
        // concatWith : les sources ne partent qu'après le dernier token, et jamais en cas d'échec
        .concatWith(Mono.fromSupplier(() -> ChatEvents.sources(promptFactory.sources(chunks))));
  }
}
