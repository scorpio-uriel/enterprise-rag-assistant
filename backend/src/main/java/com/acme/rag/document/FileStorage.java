package com.acme.rag.document;

import com.acme.rag.common.RagProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copie les fichiers importés sur le volume. Indispensable : le {@code MultipartFile} de Spring est
 * supprimé à la fin de la requête, alors que l'indexation (J4) a lieu après la réponse. Le nom sur
 * disque est {@code {uuid}.{ext}}, jamais le nom fourni par le client (traversée de répertoires).
 */
@Component
public class FileStorage {

  private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

  private final Path root;

  public FileStorage(RagProperties properties) {
    this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root); // échoue au démarrage si le volume est inutilisable
    } catch (IOException e) {
      throw new UncheckedIOException("Dossier de stockage inutilisable : " + root, e);
    }
  }

  public Path store(InputStream content, UUID id, String extension) {
    Path target = root.resolve(id + "." + extension);
    try (content) {
      Files.copy(content, target);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("Écriture du fichier impossible : " + target, e);
    }
  }

  public Path resolve(String storagePath) {
    return Path.of(storagePath);
  }

  /** Ne fait jamais échouer l'appelant : un fichier orphelin est moins grave qu'une erreur 500. */
  public void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Suppression du fichier impossible : {}", path, e);
    }
  }
}
