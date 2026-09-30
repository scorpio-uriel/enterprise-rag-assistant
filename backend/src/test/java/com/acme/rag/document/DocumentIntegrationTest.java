package com.acme.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.acme.rag.auth.Role;
import com.acme.rag.auth.TokenService;
import com.acme.rag.common.RagProperties;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Parcours complets sur un vrai PostgreSQL (Testcontainers). {@code RANDOM_PORT} démarre aussi un
 * vrai Tomcat : indispensable pour AC2.3, car MockMvc ne passe pas par le parseur multipart qui
 * applique {@code max-file-size}. Chaque import déclenche une indexation asynchrone (avec le {@code
 * FakeEmbeddingModel}) : on attend sa fin avant de nettoyer ou d'asserter un statut.
 */
@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@RecordApplicationEvents
@ActiveProfiles("test")
class DocumentIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired TokenService tokenService;
  @Autowired DocumentRepository documentRepository;
  @Autowired RagProperties ragProperties;
  @Autowired ApplicationEvents events;
  @Autowired JdbcTemplate jdbc;

  @LocalServerPort int port;

  private String adminToken;
  private Path storageDir;

  @BeforeEach
  void setUp() throws IOException {
    storageDir = Path.of(ragProperties.storageDir()).toAbsolutePath();
    DocumentTestSupport.reset(documentRepository, jdbc, storageDir);
    adminToken = tokenService.issue("admin@acme.local", Role.ADMIN).token();
  }

  @AfterEach
  void waitForBackgroundIndexing() {
    DocumentTestSupport.awaitNoIndexing(documentRepository);
  }

  @Test
  void userCannotUpload() throws Exception { // AC2.1, avec un vrai JWT
    String userToken = tokenService.issue("user@acme.local", Role.USER).token();

    mockMvc
        .perform(upload(fixture("sample.txt", "sample.txt"), userToken))
        .andExpect(status().isForbidden());

    assertThat(documentRepository.count()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"sample.pdf", "sample.docx", "sample.md", "sample.txt"})
  void adminUploadsSupportedFormat(String fileName) throws Exception { // AC2.2, puis indexation
    String body =
        mockMvc
            .perform(upload(fixture(fileName, fileName), adminToken))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

    Document document = documentRepository.findById(id).orElseThrow();
    assertThat(document.getFileName()).isEqualTo(fileName);
    assertThat(Path.of(document.getStoragePath())).exists().startsWith(storageDir);
    assertThat(events.stream(DocumentUploadedEvent.class))
        .containsExactly(new DocumentUploadedEvent(id));

    Document indexed = DocumentTestSupport.awaitIndexingEnd(documentRepository, id);
    assertThat(indexed.getStatus()).isEqualTo(DocumentStatus.INDEXED); // chaque lecteur fonctionne
    assertThat(indexed.getChunkCount()).isPositive();
  }

  @Test
  void fileOver20MegabytesIsRejectedByTomcatWith400() { // AC2.3, vraie requête HTTP
    byte[] content = new byte[21 * 1024 * 1024];
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return "gros.txt";
          }
        });

    ResponseEntity<String> response =
        RestClient.create("http://localhost:" + port)
            .post()
            .uri("/api/documents")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, res) -> {}) // pas d'exception sur 4xx
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("dépasse la taille maximale de 20 Mo");
    assertThat(documentRepository.count()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"fake.exe", "fake-exe.pdf"})
  void executableIsRejected(String fileName) throws Exception { // AC2.4
    mockMvc
        .perform(upload(fixture(fileName, fileName), adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Fichier invalide"));

    assertThat(documentRepository.count()).isZero();
    try (Stream<Path> files = Files.list(storageDir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void sameContentUploadedTwiceIsConflict() throws Exception { // AC2.5
    mockMvc
        .perform(upload(fixture("sample.pdf", "sample.pdf"), adminToken))
        .andExpect(status().isAccepted());

    mockMvc
        .perform(upload(fixture("sample.pdf", "copie.pdf"), adminToken)) // autre nom, même contenu
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Ce document a déjà été importé"));

    assertThat(documentRepository.count()).isEqualTo(1);
  }

  @Test
  void listReturnsDocumentsWithStatusNewestFirst() throws Exception { // AC4.1
    mockMvc
        .perform(upload(fixture("sample.txt", "sample.txt"), adminToken))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(upload(fixture("sample.md", "sample.md"), adminToken))
        .andExpect(status().isAccepted());
    DocumentTestSupport.awaitNoIndexing(documentRepository);

    mockMvc
        .perform(get("/api/documents").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].fileName").value("sample.md"))
        .andExpect(jsonPath("$[0].status").value("INDEXED"))
        .andExpect(jsonPath("$[0].contentType").value(containsString("text/")))
        .andExpect(jsonPath("$[0].sizeBytes").isNumber())
        .andExpect(jsonPath("$[0].chunkCount").value(greaterThan(0)))
        .andExpect(jsonPath("$[1].fileName").value("sample.txt"));
  }

  @Test
  void deletingUnknownIdIsNotFound() throws Exception { // AC4.3
    mockMvc
        .perform(
            delete("/api/documents/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingExistingDocumentRemovesRowAndFile() throws Exception {
    String body =
        mockMvc
            .perform(upload(fixture("sample.txt", "sample.txt"), adminToken))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    Path stored = Path.of(documentRepository.findById(id).orElseThrow().getStoragePath());
    DocumentTestSupport.awaitIndexingEnd(documentRepository, id); // sinon 409 si INDEXING

    mockMvc
        .perform(delete("/api/documents/{id}", id).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    assertThat(documentRepository.existsById(id)).isFalse();
    assertThat(stored).doesNotExist();
  }

  private static RequestBuilder upload(MockMultipartFile file, String token) {
    return multipart("/api/documents").file(file).header("Authorization", "Bearer " + token);
  }

  private static MockMultipartFile fixture(String resource, String fileName) throws IOException {
    try (InputStream in = DocumentIntegrationTest.class.getResourceAsStream("/files/" + resource)) {
      return new MockMultipartFile("file", fileName, "application/octet-stream", in);
    }
  }
}
