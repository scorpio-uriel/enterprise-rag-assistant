package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.chat.dto.ChatAnswer;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/**
 * Recherche réelle dans pgvector ({@code FakeEmbeddingModel}) et LLM mocké. Le corpus est {@code
 * sample.txt} (« Horaires du support informatique… »). Pas de {@code @Transactional} : voir {@link
 * DocumentTestSupport}.
 */
@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@ActiveProfiles("test")
class ChatIntegrationTest {

  private static final String OUT_OF_CORPUS = "Quelle est la capitale du Pérou ?";
  private static final String IN_CORPUS = "Quels sont les horaires du support informatique ?";

  @Autowired ChatService chatService;
  @Autowired ChatModel chatModel; // le mock @Primary de TestAiConfig
  @Autowired ChatClient.Builder chatClientBuilder;
  @Autowired VectorStore vectorStore;
  @Autowired RagPromptFactory promptFactory;
  @Autowired RagProperties ragProperties;
  @Autowired DocumentService documentService;
  @Autowired DocumentRepository documentRepository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void indexCorpus() throws IOException {
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
    ChatAnswer answer = chatService.answer(OUT_OF_CORPUS);

    assertThat(answer.answer())
        .isEqualTo("Je ne trouve pas cette information dans les documents disponibles.");
    assertThat(answer.sources()).isEmpty();
    verifyNoInteractions(chatModel);
  }

  @Test
  void relevantQuestionIsAnsweredWithRagPromptAndSources() { // AC5.2
    when(chatModel.call(any(Prompt.class))).thenReturn(response("De 8h à 18h en semaine."));

    ChatAnswer answer = chatService.answer(IN_CORPUS);

    assertThat(answer.answer()).isEqualTo("De 8h à 18h en semaine.");
    assertThat(answer.sources())
        .isNotEmpty()
        .allSatisfy(
            source -> {
              assertThat(source.fileName()).isEqualTo("sample.txt");
              assertThat(source.excerpt()).hasSizeLessThanOrEqualTo(300);
              assertThat(source.score())
                  .isGreaterThanOrEqualTo(ragProperties.similarityThreshold());
            });

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
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
    when(chatModel.call(any(Prompt.class))).thenReturn(response("De 8h à 18h en semaine."));
    ChatService service =
        new ChatService(
            new RetrievalService(vectorStore, withThreshold(threshold)),
            promptFactory,
            chatClientBuilder);

    ChatAnswer answer = service.answer(IN_CORPUS);

    assertThat(answer.answer().equals(RagPromptFactory.REFUSAL)).isEqualTo(refused);
    assertThat(answer.sources().isEmpty()).isEqualTo(refused);
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
