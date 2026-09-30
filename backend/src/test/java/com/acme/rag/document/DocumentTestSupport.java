package com.acme.rag.document;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outils des tests d'intégration qui déclenchent l'indexation asynchrone. Ces tests ne doivent pas
 * être {@code @Transactional} : la transaction du test serait annulée, jamais validée, et
 * l'écouteur {@code AFTER_COMMIT} ne se déclencherait pas. On nettoie donc à la main.
 */
public final class DocumentTestSupport {

  public static final Duration INDEXING_TIMEOUT = Duration.ofSeconds(60); // AC3.1

  private static final Set<DocumentStatus> IN_PROGRESS =
      EnumSet.of(DocumentStatus.PENDING, DocumentStatus.INDEXING);

  private DocumentTestSupport() {}

  /** Attend la fin de l'indexation d'un document et renvoie son état final. */
  public static Document awaitIndexingEnd(DocumentRepository repository, UUID id) {
    return await()
        .atMost(INDEXING_TIMEOUT)
        .until(
            () -> repository.findById(id).orElseThrow(),
            document -> !IN_PROGRESS.contains(document.getStatus()));
  }

  /** Attend qu'aucune indexation ne tourne encore (sinon elle écrirait pendant le nettoyage). */
  public static void awaitNoIndexing(DocumentRepository repository) {
    await()
        .atMost(INDEXING_TIMEOUT)
        .until(
            () ->
                repository.findAll().stream()
                    .noneMatch(document -> IN_PROGRESS.contains(document.getStatus())));
  }

  /** Vide vecteurs, documents et dossier d'upload, une fois les indexations terminées. */
  public static void reset(DocumentRepository repository, JdbcTemplate jdbc, Path storageDir)
      throws IOException {
    awaitNoIndexing(repository);
    jdbc.update("DELETE FROM vector_store");
    repository.deleteAll();
    try (Stream<Path> files = Files.list(storageDir)) {
      for (Path file : files.toList()) {
        Files.delete(file);
      }
    }
  }

  public static int vectorCount(JdbcTemplate jdbc, UUID documentId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = ?",
        Integer.class,
        documentId.toString());
  }
}
