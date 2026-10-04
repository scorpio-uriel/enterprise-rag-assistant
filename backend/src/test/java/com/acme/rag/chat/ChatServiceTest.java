package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.acme.rag.TestAiConfig;
import com.acme.rag.chat.dto.ChatRequest;
import com.acme.rag.chat.dto.DoneEvent;
import com.acme.rag.chat.dto.ErrorEvent;
import com.acme.rag.chat.dto.SourceDto;
import com.acme.rag.chat.dto.TokenEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * {@code StepVerifier} s'abonne au flux et vérifie les événements un par un, dans l'ordre, puis la
 * fin du flux ({@code verifyComplete}).
 */
class ChatServiceTest {

  private static final String QUESTION = "Combien de jours de télétravail ?";

  private final RetrievalService retrievalService = mock(RetrievalService.class);
  private final ChatModel chatModel =
      mock(ChatModel.class, withSettings().defaultAnswer(TestAiConfig::withDefaultOptions));
  private final ChatService chatService =
      new ChatService(retrievalService, new RagPromptFactory(), ChatClient.builder(chatModel));

  @Test
  void refusesWithoutCallingTheModelWhenNothingIsRelevant() { // AC8.1
    when(retrievalService.retrieve(any())).thenReturn(List.of());

    StepVerifier.create(chatService.ask(new ChatRequest(null, "Quelle est la capitale du Pérou ?")))
        .assertNext(e -> assertToken(e, RagPromptFactory.REFUSAL))
        .assertNext(e -> assertThat(e.event()).isEqualTo(ChatEvents.DONE)) // pas de sources
        .verifyComplete();
    verifyNoInteractions(chatModel);
  }

  @Test
  void streamsTokensThenSourcesThenDone() {
    UUID conversationId = UUID.randomUUID();
    givenOneRelevantChunk("Le télétravail est autorisé deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("Deux "), response("jours "), response("par semaine.")));

    StepVerifier.create(chatService.ask(new ChatRequest(conversationId, QUESTION)))
        .assertNext(e -> assertToken(e, "Deux "))
        .assertNext(e -> assertToken(e, "jours "))
        .assertNext(e -> assertToken(e, "par semaine."))
        .assertNext(
            e -> {
              assertThat(e.event()).isEqualTo(ChatEvents.SOURCES);
              assertThat(e.data())
                  .asInstanceOf(LIST)
                  .singleElement()
                  .isInstanceOfSatisfying(SourceDto.class, s -> assertThat(s.page()).isEqualTo(2));
            })
        .assertNext(
            e -> {
              assertThat(e.event()).isEqualTo(ChatEvents.DONE);
              DoneEvent done = (DoneEvent) e.data();
              assertThat(done.conversationId()).isEqualTo(conversationId);
              assertThat(done.messageId()).isNotNull();
            })
        .verifyComplete();
  }

  @Test
  void newConversationGetsAGeneratedId() {
    when(retrievalService.retrieve(any())).thenReturn(List.of());

    List<ServerSentEvent<?>> events =
        chatService.ask(new ChatRequest(null, QUESTION)).collectList().block();

    assertThat(events)
        .last()
        .satisfies(e -> assertThat(((DoneEvent) e.data()).conversationId()).isNotNull());
  }

  @Test
  void ignoresEmptyFragments() { // Ollama termine par un fragment vide
    givenOneRelevantChunk("Deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("Deux jours"), response("")));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION)))
        .assertNext(e -> assertToken(e, "Deux jours"))
        .expectNextMatches(e -> e.event().equals(ChatEvents.SOURCES))
        .expectNextMatches(e -> e.event().equals(ChatEvents.DONE))
        .verifyComplete();
  }

  @Test
  void modelFailureMidStreamEndsWithAnErrorEvent() {
    givenOneRelevantChunk("Deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            Flux.concat(
                Flux.just(response("Deux ")), Flux.error(new IllegalStateException("Ollama HS"))));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION)))
        .assertNext(e -> assertToken(e, "Deux "))
        .assertNext(this::assertError) // ni sources ni done
        .verifyComplete(); // le flux se termine normalement : la connexion n'est pas cassée
  }

  @Test
  void retrievalFailureProducesAnErrorEvent() {
    when(retrievalService.retrieve(any())).thenThrow(new IllegalStateException("pgvector HS"));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION)))
        .assertNext(this::assertError)
        .verifyComplete();
  }

  @Test
  void bracesInExcerptsOrQuestionDoNotBreakThePrompt() { // pas d'interprétation comme template
    when(retrievalService.retrieve(any()))
        .thenReturn(List.of(new Document("Config : {\"mode\": \"strict\"}", Map.of())));
    when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("ok")));

    chatService.ask(new ChatRequest(null, "Que vaut {mode} ?")).blockLast();

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(prompt.capture());
    assertThat(prompt.getValue().getSystemMessage().getText()).contains("{\"mode\": \"strict\"}");
    assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("Que vaut {mode} ?");
  }

  private void givenOneRelevantChunk(String text) {
    when(retrievalService.retrieve(any()))
        .thenReturn(
            List.of(
                Document.builder()
                    .text(text)
                    .metadata(Map.of("fileName", "teletravail.pdf", "page", 2))
                    .score(0.8)
                    .build()));
  }

  private static void assertToken(ServerSentEvent<?> event, String text) {
    assertThat(event.event()).isEqualTo(ChatEvents.TOKEN);
    assertThat(event.data()).isEqualTo(new TokenEvent(text));
  }

  private void assertError(ServerSentEvent<?> event) {
    assertThat(event.event()).isEqualTo(ChatEvents.ERROR);
    assertThat(event.data()).isEqualTo(new ErrorEvent(ChatService.MODEL_ERROR));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
