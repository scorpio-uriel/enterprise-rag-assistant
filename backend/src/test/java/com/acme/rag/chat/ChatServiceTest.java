package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.acme.rag.common.NotFoundException;
import com.acme.rag.conversation.ChatSession;
import com.acme.rag.conversation.ConversationService;
import com.acme.rag.conversation.Message;
import com.acme.rag.conversation.MessageRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
  private static final String EMAIL = "user@acme.local";
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID MESSAGE_ID = UUID.randomUUID();

  private final RetrievalService retrievalService = mock(RetrievalService.class);
  private final ConversationService conversationService = mock(ConversationService.class);
  private final ChatModel chatModel =
      mock(ChatModel.class, withSettings().defaultAnswer(TestAiConfig::withDefaultOptions));
  private final ChatService chatService =
      new ChatService(
          retrievalService,
          new RagPromptFactory(),
          conversationService,
          ChatClient.builder(chatModel));

  /** Par défaut : nouvelle conversation sans historique, enregistrement réussi. */
  @BeforeEach
  void givenANewConversation() {
    when(conversationService.open(any(), anyString()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return new ChatSession(
                  id != null ? id : UUID.randomUUID(), USER_ID, id == null, List.of());
            });
    when(conversationService.recordExchange(any(), any(), any(), any(), any()))
        .thenReturn(MESSAGE_ID);
  }

  @Test
  void refusesWithoutCallingTheModelWhenNothingIsRelevant() { // AC8.1
    when(retrievalService.retrieve(any())).thenReturn(List.of());

    StepVerifier.create(
            chatService.ask(new ChatRequest(null, "Quelle est la capitale du Pérou ?"), EMAIL))
        .assertNext(e -> assertToken(e, RagPromptFactory.REFUSAL))
        .assertNext(e -> assertThat(e.event()).isEqualTo(ChatEvents.DONE)) // pas de sources
        .verifyComplete();
    verifyNoInteractions(chatModel);
    // le refus est enregistré aussi, sans sources
    verify(conversationService)
        .recordExchange(
            any(),
            eq("Quelle est la capitale du Pérou ?"),
            any(),
            eq(RagPromptFactory.REFUSAL),
            isNull());
  }

  @Test
  void streamsTokensThenSourcesThenDone() {
    UUID conversationId = UUID.randomUUID();
    givenOneRelevantChunk("Le télétravail est autorisé deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("Deux "), response("jours "), response("par semaine.")));

    StepVerifier.create(chatService.ask(new ChatRequest(conversationId, QUESTION), EMAIL))
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
              assertThat(done.messageId()).isEqualTo(MESSAGE_ID);
            })
        .verifyComplete();
  }

  @Test
  void recordsTheFullAnswerWithItsSources() {
    givenOneRelevantChunk("Le télétravail est autorisé deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("Deux "), response("jours.")));

    chatService.ask(new ChatRequest(null, QUESTION), EMAIL).blockLast();

    ArgumentCaptor<List<SourceDto>> sources = ArgumentCaptor.captor();
    verify(conversationService)
        .recordExchange(any(), eq(QUESTION), any(), eq("Deux jours."), sources.capture());
    assertThat(sources.getValue())
        .singleElement()
        .extracting(SourceDto::fileName)
        .isEqualTo("teletravail.pdf");
  }

  @Test
  void historyIsInjectedInChronologicalOrderBetweenSystemAndQuestion() { // AC7.2
    UUID conversationId = UUID.randomUUID();
    List<Message> history =
        List.of(
            new Message(null, MessageRole.USER, "Question 1", null, Instant.now()),
            new Message(null, MessageRole.ASSISTANT, "Réponse 1", null, Instant.now()));
    when(conversationService.open(eq(conversationId), anyString()))
        .thenReturn(new ChatSession(conversationId, USER_ID, false, history));
    givenOneRelevantChunk("Deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("ok")));

    chatService.ask(new ChatRequest(conversationId, QUESTION), EMAIL).blockLast();

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(prompt.capture());
    assertThat(prompt.getValue().getInstructions())
        .satisfiesExactly(
            m -> assertThat(m).isInstanceOf(SystemMessage.class),
            m -> assertThat(m).isEqualTo(new UserMessage("Question 1")),
            m -> assertThat(m).isEqualTo(new AssistantMessage("Réponse 1")),
            m -> assertThat(m).isEqualTo(new UserMessage(QUESTION)));
  }

  @Test
  void unknownOrForeignConversationFailsBeforeTheStream() { // AC7.3 : 404 HTTP, pas d'événement
    UUID foreign = UUID.randomUUID();
    when(conversationService.open(eq(foreign), anyString()))
        .thenThrow(new NotFoundException("Conversation introuvable"));

    assertThatThrownBy(() -> chatService.ask(new ChatRequest(foreign, QUESTION), EMAIL))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(retrievalService, chatModel);
  }

  @Test
  void persistenceFailureEndsWithAnErrorEvent() {
    when(retrievalService.retrieve(any())).thenReturn(List.of());
    when(conversationService.recordExchange(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Postgres HS"));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION), EMAIL))
        .assertNext(e -> assertToken(e, RagPromptFactory.REFUSAL))
        .assertNext(this::assertError) // pas de done
        .verifyComplete();
  }

  @Test
  void newConversationGetsAGeneratedId() {
    when(retrievalService.retrieve(any())).thenReturn(List.of());

    List<ServerSentEvent<?>> events =
        chatService.ask(new ChatRequest(null, QUESTION), EMAIL).collectList().block();

    assertThat(events)
        .last()
        .satisfies(e -> assertThat(((DoneEvent) e.data()).conversationId()).isNotNull());
  }

  @Test
  void ignoresEmptyFragments() { // Ollama termine par un fragment vide
    givenOneRelevantChunk("Deux jours par semaine.");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("Deux jours"), response("")));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION), EMAIL))
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

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION), EMAIL))
        .assertNext(e -> assertToken(e, "Deux "))
        .assertNext(this::assertError) // ni sources ni done
        .verifyComplete(); // le flux se termine normalement : la connexion n'est pas cassée
    verify(conversationService, never()).recordExchange(any(), any(), any(), any(), any());
  }

  @Test
  void retrievalFailureProducesAnErrorEvent() {
    when(retrievalService.retrieve(any())).thenThrow(new IllegalStateException("pgvector HS"));

    StepVerifier.create(chatService.ask(new ChatRequest(null, QUESTION), EMAIL))
        .assertNext(this::assertError)
        .verifyComplete();
  }

  @Test
  void bracesInExcerptsOrQuestionDoNotBreakThePrompt() { // pas d'interprétation comme template
    when(retrievalService.retrieve(any()))
        .thenReturn(List.of(new Document("Config : {\"mode\": \"strict\"}", Map.of())));
    when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("ok")));

    chatService.ask(new ChatRequest(null, "Que vaut {mode} ?"), EMAIL).blockLast();

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
