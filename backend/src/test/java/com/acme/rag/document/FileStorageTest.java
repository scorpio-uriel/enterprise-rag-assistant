package com.acme.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.common.RagProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class FileStorageTest {

  @TempDir Path tempDir; // dossier temporaire créé et nettoyé par JUnit

  @Test
  void storesFileAsUuidWithExtension() throws IOException {
    FileStorage storage = storageIn(tempDir.resolve("uploads"));
    UUID id = UUID.randomUUID();

    Path stored = storage.store(new ByteArrayInputStream("contenu".getBytes()), id, "pdf");

    assertThat(stored).isEqualTo(tempDir.resolve("uploads").resolve(id + ".pdf"));
    assertThat(Files.readString(stored)).isEqualTo("contenu");
  }

  @Test
  void deleteRemovesFileAndToleratesMissingOne() {
    FileStorage storage = storageIn(tempDir);
    Path stored = storage.store(new ByteArrayInputStream(new byte[] {1}), UUID.randomUUID(), "txt");

    storage.delete(stored);
    storage.delete(stored); // déjà supprimé : aucune exception

    assertThat(stored).doesNotExist();
  }

  private static FileStorage storageIn(Path dir) {
    return new FileStorage(new RagProperties(dir.toString(), DataSize.ofMegabytes(20), 800, 100));
  }
}
