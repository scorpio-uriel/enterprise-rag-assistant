package com.acme.rag.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.chat.ChatEvents;
import com.acme.rag.chat.SseTestSupport;
import com.acme.rag.chat.SseTestSupport.SseEvent;
import com.acme.rag.chat.dto.ChatRequest;
import com.acme.rag.common.RagProperties;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parcours complet par HTTP : chat SSE → persistance → API des conversations. Corpus {@code
 * sample.txt}, LLM mocké. Pas de {@code @Transactional} : voir {@link DocumentTestSupport}.
 */
@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationIntegrationTest {

  private static final String USER = "user@acme.local";
  private static final String ADMIN = "admin@acme.local";
  private static final String QUESTION = "Quels sont les horaires du support informatique ?";
  private static final String FOLLOW_UP = "Et les horaires du support informatique le vendredi ?";
  private static final String ANSWER = "De 8h à 18h en semaine.";

  @Autowired MockMvc mockMvc;
  @Autowired ChatModel chatModel; // le mock @Primary de TestAiConfig
  @Autowired DocumentService documentService;
  @Autowired DocumentRepository documentRepository;
  @Autowired RagProperties ragProperties;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper objectMapper;

  @BeforeEach
  void indexCorpus() throws IOException {
    jdbc.update("DELETE FROM conversation"); // les messages suivent (ON DELETE CASCADE)
    DocumentTestSupport.reset(
        documentRepository, jdbc, Path.of(ragProperties.storageDir()).toAbsolutePath());
    UUID id;
    try (InputStream in = getClass().getResourceAsStream("/files/sample.txt")) {
      id =
          documentService
              .upload(new MockMultipartFile("file", "sample.txt", "text/plain", in), ADMIN)
              .id();
    }
    assertThat(DocumentTestSupport.awaitIndexingEnd(documentRepository, id).getStatus())
        .isEqualTo(DocumentStatus.INDEXED);
  }

  @AfterEach
  void waitForBackgroundIndexing() {
    DocumentTestSupport.awaitNoIndexing(documentRepository);
  }

  @Test
  void newConversationIsCreatedAndListed() throws Exception { // AC7.1
    givenModelAnswers();

    UUID conversationId = chat(USER, null, QUESTION);

    JsonNode list = getJson(USER, "/api/conversations");
    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("id").asText()).isEqualTo(conversationId.toString());
    assertThat(list.get(0).get("title").asText()).isEqualTo(QUESTION); // < 60 caractères
    assertThat(getJson(ADMIN, "/api/conversations")).isEmpty(); // liste privée
  }

  @Test
  void followUpPromptContainsThePreviousExchange() throws Exception { // AC7.2
    givenModelAnswers();
    UUID conversationId = chat(USER, null, QUESTION);

    assertThat(chat(USER, conversationId, FOLLOW_UP)).isEqualTo(conversationId);

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).stream(prompts.capture());
    assertThat(prompts.getAllValues().get(1).getInstructions())
        .satisfiesExactly(
            m -> assertThat(m).isInstanceOf(SystemMessage.class),
            m -> assertThat(m).isEqualTo(new UserMessage(QUESTION)),
            m -> assertThat(m).isEqualTo(new AssistantMessage(ANSWER)),
            m -> assertThat(m).isEqualTo(new UserMessage(FOLLOW_UP)));
  }

  @Test
  void detailReturnsMessagesWithTheirSources() throws Exception {
    givenModelAnswers();
    UUID conversationId = chat(USER, null, QUESTION);

    JsonNode detail = getJson(USER, "/api/conversations/" + conversationId);

    assertThat(detail.get("title").asText()).isEqualTo(QUESTION);
    JsonNode messages = detail.get("messages");
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).get("role").asText()).isEqualTo("USER");
    assertThat(messages.get(0).get("content").asText()).isEqualTo(QUESTION);
    assertThat(messages.get(1).get("role").asText()).isEqualTo("ASSISTANT");
    assertThat(messages.get(1).get("content").asText()).isEqualTo(ANSWER);
    JsonNode source = messages.get(1).get("sources").get(0); // relu depuis le JSONB
    assertThat(source.get("fileName").asText()).isEqualTo("sample.txt");
    assertThat(source.get("excerpt").asText()).contains("Horaires du support informatique");
    assertThat(source.get("score").asDouble()).isPositive();
  }

  @Test
  void refusalIsPersistedToo() throws Exception {
    UUID conversationId = chat(USER, null, "Quelle est la capitale du Pérou ?");

    JsonNode messages = getJson(USER, "/api/conversations/" + conversationId).get("messages");
    assertThat(messages).hasSize(2);
    assertThat(messages.get(1).get("content").asText())
        .isEqualTo("Je ne trouve pas cette information dans les documents disponibles.");
    assertThat(messages.get(1).get("sources").isNull()).isTrue();
  }

  @Test
  void anotherUsersConversationIsNotFound() throws Exception { // AC7.3
    givenModelAnswers();
    UUID adminConversation = chat(ADMIN, null, QUESTION);

    mockMvc
        .perform(get("/api/conversations/{id}", adminConversation).with(as(USER)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/chat")
                .with(as(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM) // comme le front
                .content(
                    objectMapper.writeValueAsString(new ChatRequest(adminConversation, FOLLOW_UP))))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/conversations/{id}", UUID.randomUUID()).with(as(USER)))
        .andExpect(status().isNotFound()); // inexistante : même réponse
    verify(chatModel, times(1)).stream(any(Prompt.class)); // seulement la question de l'admin
  }

  /** Pose une question par HTTP et renvoie le {@code conversationId} de l'événement done. */
  private UUID chat(String email, UUID conversationId, String question) throws Exception {
    String body =
        SseTestSupport.perform(
                mockMvc,
                post("/api/chat")
                    .with(as(email))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content(
                        objectMapper.writeValueAsString(new ChatRequest(conversationId, question))))
            .getResponse()
            .getContentAsString();
    List<SseEvent> events = SseTestSupport.parse(body);
    assertThat(events).last().extracting(SseEvent::name).isEqualTo(ChatEvents.DONE);
    JsonNode done = objectMapper.readTree(events.getLast().data());
    assertThat(done.get("messageId").asText()).isNotBlank();
    return UUID.fromString(done.get("conversationId").asText());
  }

  private JsonNode getJson(String email, String url) throws Exception {
    String body =
        mockMvc
            .perform(get(url).with(as(email)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private static RequestPostProcessor as(String email) {
    String role = email.equals(ADMIN) ? "ROLE_ADMIN" : "ROLE_USER";
    return jwt().jwt(j -> j.subject(email)).authorities(new SimpleGrantedAuthority(role));
  }

  /** Une nouvelle réponse à chaque appel (un même Flux ne se rejoue pas toujours). */
  private void givenModelAnswers() {
    when(chatModel.stream(any(Prompt.class)))
        .thenAnswer(
            inv -> Flux.just(response("De 8h "), response("à 18h "), response("en semaine.")));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
