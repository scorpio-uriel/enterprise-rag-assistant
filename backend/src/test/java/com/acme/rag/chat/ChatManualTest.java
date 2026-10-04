package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.chat.dto.ChatRequest;
import com.acme.rag.chat.dto.TokenEvent;
import com.acme.rag.common.RagProperties;
import com.acme.rag.document.DocumentRepository;
import com.acme.rag.document.DocumentService;
import com.acme.rag.document.DocumentStatus;
import com.acme.rag.document.DocumentTestSupport;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

/**
 * Vérification manuelle avec le vrai Ollama (llama3.1:8b + nomic-embed-text), exclue de {@code mvn
 * verify}. Lancer : {@code ./mvnw test -Dsurefire.excludedGroups=none -Dgroups=manual}. Le seuil de
 * production (0.55) est repris, puisque les embeddings sont réels.
 */
@Tag("manual")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "rag.similarity-threshold=0.55")
@ActiveProfiles("test")
class ChatManualTest {

  @Autowired ChatService chatService;
  @Autowired DocumentService documentService;
  @Autowired DocumentRepository documentRepository;
  @Autowired RagProperties ragProperties;
  @Autowired JdbcTemplate jdbc;

  @Test
  void realOllamaAnswersAQuestionAboutTheTestDocument() throws Exception {
    DocumentTestSupport.reset(
        documentRepository, jdbc, Path.of(ragProperties.storageDir()).toAbsolutePath());
    UUID id;
    try (InputStream in = getClass().getResourceAsStream("/files/sample.txt")) {
      id =
          documentService
              .upload(
                  new MockMultipartFile("file", "sample.txt", "text/plain", in), "admin@acme.local")
              .id();
    }
    assertThat(DocumentTestSupport.awaitIndexingEnd(documentRepository, id).getStatus())
        .isEqualTo(DocumentStatus.INDEXED);

    List<ServerSentEvent<?>> events =
        chatService
            .ask(new ChatRequest(null, "Quels sont les horaires du support informatique ?"))
            .collectList()
            .block();

    StringBuilder answer = new StringBuilder();
    events.stream()
        .filter(e -> ChatEvents.TOKEN.equals(e.event()))
        .forEach(e -> answer.append(((TokenEvent) e.data()).text()));
    System.out.println("Réponse : " + answer + "\nÉvénements : " + events);
    assertThat(answer.toString()).isNotBlank().isNotEqualTo(RagPromptFactory.REFUSAL);
    assertThat(events).anyMatch(e -> ChatEvents.SOURCES.equals(e.event()));
    assertThat(events).last().matches(e -> ChatEvents.DONE.equals(e.event()));
  }
}
