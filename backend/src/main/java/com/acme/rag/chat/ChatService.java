package com.acme.rag.chat;

import com.acme.rag.chat.dto.ChatRequest;
import com.acme.rag.chat.dto.SourceDto;
import com.acme.rag.conversation.ChatSession;
import com.acme.rag.conversation.ConversationService;
import com.acme.rag.conversation.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Orchestre recherche → décision de refus → appel au LLM (avec l'historique) → persistance, sous
 * forme de flux d'événements SSE : {@code token}…, {@code sources}, {@code done} ; ou {@code token}
 * (refus) puis {@code done} ; ou {@code error} en cas d'échec. Volontairement sans
 * {@code @Transactional} : la transaction se fermerait avant que le flux ne produise quoi que ce
 * soit. L'échange est enregistré à la fin, par {@link ConversationService#recordExchange}.
 */
@Service
public class ChatService {

  static final String MODEL_ERROR = "Le modèle ne répond pas";

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private final RetrievalService retrievalService;
  private final RagPromptFactory promptFactory;
  private final ConversationService conversationService;
  private final ChatClient chatClient;

  /** {@code ChatClient.Builder} est auto-configuré par Spring AI autour du {@code ChatModel}. */
  public ChatService(
      RetrievalService retrievalService,
      RagPromptFactory promptFactory,
      ConversationService conversationService,
      ChatClient.Builder chatClientBuilder) {
    this.retrievalService = retrievalService;
    this.promptFactory = promptFactory;
    this.conversationService = conversationService;
    this.chatClient = chatClientBuilder.build();
  }

  /**
   * La conversation est chargée tout de suite, hors du flux : celle d'un autre utilisateur lève une
   * {@code NotFoundException} dans le thread de la requête, traduite en vrai 404 HTTP (AC7.3)
   * plutôt qu'en événement {@code error}.
   */
  public Flux<ServerSentEvent<?>> ask(ChatRequest request, String userEmail) {
    ChatSession session = conversationService.open(request.conversationId(), userEmail);
    Instant askedAt = Instant.now();
    // defer : la recherche ne s'exécute qu'à l'abonnement, et ses exceptions deviennent des
    // erreurs du flux, rattrapées par onErrorResume comme celles du LLM et de la persistance.
    return Flux.defer(
            () -> {
              Answer answer = new Answer(); // propre à cet abonnement
              return answerEvents(request.question(), session.history(), answer)
                  .concatWith(
                      // JPA est bloquant : on quitte le thread réseau du client du LLM.
                      Mono.fromCallable(
                              () ->
                                  conversationService.recordExchange(
                                      session,
                                      request.question(),
                                      askedAt,
                                      answer.text.toString(),
                                      answer.sources))
                          .subscribeOn(Schedulers.boundedElastic())
                          .map(messageId -> ChatEvents.done(session.conversationId(), messageId)));
            })
        .onErrorResume(
            e -> {
              log.error("Échec de la génération de la réponse", e);
              return Flux.just(ChatEvents.error(MODEL_ERROR));
            });
  }

  private Flux<ServerSentEvent<?>> answerEvents(
      String question, List<Message> history, Answer answer) {
    List<Document> chunks = retrievalService.retrieve(question);
    if (chunks.isEmpty()) {
      answer.text.append(RagPromptFactory.REFUSAL);
      return Flux.just(ChatEvents.token(RagPromptFactory.REFUSAL)); // LLM jamais appelé (AC8.1)
    }
    answer.sources = promptFactory.sources(chunks);
    return chatClient
        .prompt()
        // Messages déjà construits : .system(String) passerait le texte au moteur de template,
        // qui échouerait sur les accolades présentes dans un extrait.
        .messages(promptMessages(chunks, history))
        .user(question)
        .stream()
        .content()
        .filter(text -> !text.isEmpty()) // Ollama termine par un fragment vide (done=true)
        .doOnNext(answer.text::append)
        .<ServerSentEvent<?>>map(ChatEvents::token)
        // concatWith : les sources ne partent qu'après le dernier token, et jamais en cas d'échec
        .concatWith(Mono.fromSupplier(() -> ChatEvents.sources(answer.sources)));
  }

  /** Prompt système, puis l'historique (déjà dans l'ordre chronologique) en messages typés. */
  private List<org.springframework.ai.chat.messages.Message> promptMessages(
      List<Document> chunks, List<Message> history) {
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(promptFactory.systemPrompt(chunks)));
    for (Message message : history) {
      messages.add(
          switch (message.getRole()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
          });
    }
    return messages;
  }

  /** Réponse accumulée au fil du flux, pour être enregistrée une fois complète. */
  private static final class Answer {
    private final StringBuilder text = new StringBuilder();
    private List<SourceDto> sources; // null en cas de refus
  }
}
