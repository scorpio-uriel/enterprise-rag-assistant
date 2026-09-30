package com.acme.rag.document;

import com.acme.rag.common.RagProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Vérifie taille, extension et type MIME <em>réel</em>. Tika lit les premiers octets (« nombres
 * magiques » : {@code %PDF-}, {@code PK}, {@code MZ}…) ; le nom du fichier n'est qu'un indice qui
 * ne l'emporte jamais sur une signature contradictoire (un {@code .exe} renommé reste un exe).
 */
@Component
public class FileValidator {

  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  /** Types détectés acceptés pour chaque extension (md/txt : Tika voit souvent du text/plain). */
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "pdf", Set.of("application/pdf"),
          "docx", Set.of(DOCX),
          "md", Set.of("text/plain", "text/markdown", "text/x-web-markdown"),
          "txt", Set.of("text/plain"));

  private final Tika tika = new Tika();
  private final long maxBytes;
  private final String maxLabel;

  public FileValidator(RagProperties properties) {
    this.maxBytes = properties.maxFileSize().toBytes();
    this.maxLabel = properties.maxFileSize().toMegabytes() + " Mo";
  }

  public ValidatedFile validate(MultipartFile file) {
    if (file.isEmpty()) {
      throw new InvalidFileException("Le fichier est vide");
    }
    if (file.getSize() > maxBytes) { // MockMvc et les appels directs contournent Tomcat
      throw new InvalidFileException("Le fichier dépasse la taille maximale de " + maxLabel);
    }
    String fileName = file.getOriginalFilename();
    String extension = StringUtils.getFilenameExtension(fileName);
    extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    Set<String> allowedTypes = ALLOWED.get(extension);
    if (allowedTypes == null) {
      throw new InvalidFileException(
          "Extension non autorisée : seuls les fichiers PDF, DOCX, MD et TXT sont acceptés");
    }
    String detectedType = detect(file, fileName);
    if (!allowedTypes.contains(detectedType)) {
      throw new InvalidFileException(
          "Le contenu du fichier ("
              + detectedType
              + ") ne correspond pas à l'extension ."
              + extension);
    }
    return new ValidatedFile(extension, detectedType);
  }

  private String detect(MultipartFile file, String fileName) {
    try (InputStream in = file.getInputStream()) {
      return tika.detect(in, fileName);
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture du fichier importé impossible", e);
    }
  }
}
