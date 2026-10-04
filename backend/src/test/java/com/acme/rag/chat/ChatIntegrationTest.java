package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.chat.SseTestSupport.SseEvent;
import com.acme.rag.chat.dto.ChatRequest;
import com.acme.rag.chat.dto.TokenEvent;
import com.acme.rag.common.RagProperties;
import com.acme.rag.conversation.ConversationService;
import com.acme.rag.document.DocumentRepository;
import com.acme.rag.document.DocumentService;
import com.acme.rag.document.DocumentStatus;
import com.acme.rag.document.DocumentTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recherche réelle dans pgvector ({@code FakeEmbeddingModel}) et LLM mocké. Le corpus est {@code
 * sample.txt} (« Horaires du support informatique… »). Pas de {@code @Transactional} : voir {@link
 * DocumentTestSupport}.
 */
@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatIntegrationTest {

  private static final String OUT_OF_CORPUS = "Quelle est la capitale du Pérou ?";
  private static final String IN_CORPUS = "Quels sont les horaires du support informatique ?";
  static final String USER_EMAIL = "user@acme.local"; // compte seedé par DataSeeder

  @Autowired ChatService chatService;
  @Autowired ChatModel chatModel; // le mock @Primary de TestAiConfig
  @Autowired ChatClient.Builder chatClientBuilder;
  @Autowired VectorStore vectorStore;
  @Autowired RagPromptFactory promptFactory;
  @Autowired RagProperties ragProperties;
  @Autowired ConversationService conversationService;
  @Autowired DocumentService documentService;
  @Autowired DocumentRepository documentRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper objectMapper;

  @BeforeEach
  void indexCorpus() throws IOException {
    jdbc.update("DELETE FROM conversation"); // les messages suivent (ON DELETE CASCADE)
    DocumentTestSupport.reset(
        documentRepository, jdbc, Path.of(ragProperties.storageDir()).toAbsolutePath());
    UUID id = upload("sample.txt");
    assertThat(DocumentTestSupport.awaitIndexingEnd(documentRepository, id).getStatus())
        .isEqualTo(DocumentStatus.INDEXED);
  }

  @AfterEach
  void waitForBackgroundIndexing() {
    DocumentTestSupport.awaitNoIndexing(documentRepository);
  }

  @Test
  void outOfCorpusQuestionIsRefusedWithoutCallingTheModel() { // AC8.1
    List<ServerSentEvent<?>> events = ask(chatService, OUT_OF_CORPUS);

    assertThat(answerText(events))
        .isEqualTo("Je ne trouve pas cette information dans les documents disponibles.");
    assertThat(events).noneMatch(e -> ChatEvents.SOURCES.equals(e.event()));
    verifyNoInteractions(chatModel);
  }

  @Test
  void relevantQuestionIsAnsweredWithRagPromptAndSources() { // AC5.2
    givenModelStreams();

    List<ServerSentEvent<?>> events = ask(chatService, IN_CORPUS);

    assertThat(answerText(events)).isEqualTo("De 8h à 18h en semaine.");

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(prompt.capture());
    assertThat(prompt.getValue().getSystemMessage().getText())
        .contains("en français")
        .contains("UNIQUEMENT les extraits")
        .contains("[1] (sample.txt)")
        .contains("Horaires du support informatique");
    assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo(IN_CORPUS);
  }

  /** Même question, seul le seuil change : la décision de refus suit la configuration. */
  @ParameterizedTest
  @CsvSource({"0.99, true", "0.1, false"})
  void thresholdDecidesBetweenRefusalAndAnswer(double threshold, boolean refused) { // AC8.2
    givenModelStreams();
    ChatService service =
        new ChatService(
            new RetrievalService(vectorStore, withThreshold(threshold)),
            promptFactory,
            conversationService,
            chatClientBuilder);

    List<ServerSentEvent<?>> events = ask(service, IN_CORPUS);

    assertThat(answerText(events).equals(RagPromptFactory.REFUSAL)).isEqualTo(refused);
    assertThat(events.stream().noneMatch(e -> ChatEvents.SOURCES.equals(e.event())))
        .isEqualTo(refused);
  }

  @Test
  void httpStreamSendsTokensSourcesThenDone() throws Exception { // AC5.1, AC6.1
    givenModelStreams();

    MvcResult result = postChat(IN_CORPUS);

    assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    List<SseEvent> events = SseTestSupport.parse(result.getResponse().getContentAsString());
    assertThat(events)
        .filteredOn(e -> e.name().equals(ChatEvents.TOKEN))
        .hasSizeGreaterThanOrEqualTo(2);
    assertThat(events).last().extracting(SseEvent::name).isEqualTo(ChatEvents.DONE);

    SseEvent sources =
        events.stream().filter(e -> e.name().equals(ChatEvents.SOURCES)).findFirst().orElseThrow();
    JsonNode entries = objectMapper.readTree(sources.data());
    assertThat(entries).isNotEmpty();
    for (JsonNode entry : entries) {
      assertThat(entry.get("fileName").asText()).isEqualTo("sample.txt");
      assertThat(entry.get("excerpt").asText()).hasSizeLessThanOrEqualTo(300);
      assertThat(entry.get("score").asDouble())
          .isGreaterThanOrEqualTo(ragProperties.similarityThreshold());
    }
  }

  @Test
  void httpStreamHasNoSourcesWhenRefused() throws Exception { // AC6.1
    MvcResult result = postChat(OUT_OF_CORPUS);

    List<SseEvent> events = SseTestSupport.parse(result.getResponse().getContentAsString());
    assertThat(events)
        .extracting(SseEvent::name)
        .containsExactly(ChatEvents.TOKEN, ChatEvents.DONE);
    verifyNoInteractions(chatModel);
  }

  private MvcResult postChat(String question) throws Exception {
    return SseTestSupport.perform(
        mockMvc,
        post("/api/chat")
            .with(
                jwt()
                    .jwt(j -> j.subject(USER_EMAIL))
                    .authorities(new SimpleGrantedAuthority("ROLE_USER")))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content(objectMapper.writeValueAsString(new ChatRequest(null, question))));
  }

  /** Le LLM « répond » en trois fragments, comme Ollama en streaming. */
  private void givenModelStreams() {
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("De 8h "), response("à 18h "), response("en semaine.")));
  }

  private static List<ServerSentEvent<?>> ask(ChatService service, String question) {
    return service.ask(new ChatRequest(null, question), USER_EMAIL).collectList().block();
  }

  /** Concatène les fragments des événements {@code token}. */
  private static String answerText(List<ServerSentEvent<?>> events) {
    StringBuilder text = new StringBuilder();
    events.stream()
        .filter(e -> ChatEvents.TOKEN.equals(e.event()))
        .forEach(e -> text.append(((TokenEvent) e.data()).text()));
    return text.toString();
  }

  private RagProperties withThreshold(double threshold) {
    RagProperties p = ragProperties;
    return new RagProperties(
        p.storageDir(), p.maxFileSize(), p.chunkSize(), p.chunkOverlap(), p.topK(), threshold);
  }

  private UUID upload(String fixture) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/files/" + fixture)) {
      MockMultipartFile file = new MockMultipartFile("file", fixture, "text/plain", in);
      return documentService.upload(file, "admin@acme.local").id();
    }
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
