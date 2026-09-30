package com.acme.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.auth.Role;
import com.acme.rag.auth.TokenService;
import com.acme.rag.common.RagProperties;
import com.acme.rag.document.Document;
import com.acme.rag.document.DocumentRepository;
import com.acme.rag.document.DocumentStatus;
import com.acme.rag.document.DocumentTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Import → indexation asynchrone → pgvector, sur un vrai PostgreSQL et avec le {@code
 * FakeEmbeddingModel}. Pas de {@code @Transactional} : voir {@link DocumentTestSupport}.
 */
@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired TokenService tokenService;
  @Autowired DocumentRepository documentRepository;
  @Autowired RagProperties ragProperties;
  @Autowired JdbcTemplate jdbc;
  @Autowired StuckIndexingRecovery stuckIndexingRecovery;

  private String adminToken;

  @BeforeEach
  void setUp() throws IOException {
    DocumentTestSupport.reset(
        documentRepository, jdbc, Path.of(ragProperties.storageDir()).toAbsolutePath());
    adminToken = tokenService.issue("admin@acme.local", Role.ADMIN).token();
  }

  @AfterEach
  void waitForBackgroundIndexing() {
    DocumentTestSupport.awaitNoIndexing(documentRepository);
  }

  @Test
  void importedPdfIsIndexedIntoPgvector() throws Exception { // AC3.1
    UUID id = upload("sample.pdf");

    Document document = DocumentTestSupport.awaitIndexingEnd(documentRepository, id);

    assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(document.getErrorMessage()).isNull();
    assertThat(document.getChunkCount()).isPositive();
    assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isEqualTo(document.getChunkCount());
  }

  @Test
  void chunksCarryDocumentIdFileNameAndPage() throws Exception {
    UUID id = upload("sample.pdf");
    DocumentTestSupport.awaitIndexingEnd(documentRepository, id);

    List<String> metadata =
        jdbc.queryForList(
            "SELECT metadata->>'fileName' || '|' || (metadata->>'page')"
                + " FROM vector_store WHERE metadata->>'documentId' = ?",
            String.class,
            id.toString());

    assertThat(metadata).isNotEmpty().containsOnly("sample.pdf|1");
  }

  @Test
  void corruptedPdfFailsWithoutVectors() throws Exception { // AC3.2
    UUID id = upload("corrupt.pdf");

    Document document = DocumentTestSupport.awaitIndexingEnd(documentRepository, id);

    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(document.getErrorMessage()).isNotBlank();
    assertThat(document.getChunkCount()).isZero();
    assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isZero();
  }

  @Test
  void deletingIndexedDocumentRemovesItsVectors() throws Exception { // AC4.2
    UUID id = upload("sample.pdf");
    Document document = DocumentTestSupport.awaitIndexingEnd(documentRepository, id);
    assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isPositive();

    mockMvc
        .perform(delete("/api/documents/{id}", id).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isZero();
    assertThat(documentRepository.existsById(id)).isFalse();
    assertThat(Path.of(document.getStoragePath())).doesNotExist();
  }

  @Test
  void deletingDocumentBeingIndexedIsConflict() throws Exception { // AC4.2
    UUID id = upload("sample.txt");
    DocumentTestSupport.awaitIndexingEnd(documentRepository, id);
    forceStatus(id, DocumentStatus.INDEXING); // simule une indexation en cours
    try {
      mockMvc
          .perform(
              delete("/api/documents/{id}", id).header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.title").value("Indexation en cours"));

      assertThat(documentRepository.existsById(id)).isTrue();
      assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isPositive();
    } finally {
      forceStatus(id, DocumentStatus.INDEXED); // sinon le nettoyage attendrait indéfiniment
    }
  }

  @Test
  void documentStuckInIndexingAtStartupBecomesFailed() throws Exception {
    UUID id = upload("sample.txt");
    DocumentTestSupport.awaitIndexingEnd(documentRepository, id);
    forceStatus(id, DocumentStatus.INDEXING); // comme après un arrêt brutal en pleine indexation

    stuckIndexingRecovery.run(new DefaultApplicationArguments());

    Document document = documentRepository.findById(id).orElseThrow();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(document.getErrorMessage()).isEqualTo(StuckIndexingRecovery.MESSAGE);
    assertThat(DocumentTestSupport.vectorCount(jdbc, id)).isZero();
  }

  private UUID upload(String fixture) throws Exception {
    MockMultipartFile file;
    try (InputStream in = getClass().getResourceAsStream("/files/" + fixture)) {
      file = new MockMultipartFile("file", fixture, "application/octet-stream", in);
    }
    String body =
        mockMvc
            .perform(
                multipart("/api/documents")
                    .file(file)
                    .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private void forceStatus(UUID id, DocumentStatus status) {
    jdbc.update("UPDATE document SET status = ? WHERE id = ?", status.name(), id);
  }
}
