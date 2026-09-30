package com.acme.rag.document;

import com.acme.rag.auth.UserRepository;
import com.acme.rag.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

  private static final int MAX_FILE_NAME_LENGTH = 255;

  private final DocumentRepository documentRepository;
  private final UserRepository userRepository;
  private final FileValidator fileValidator;
  private final FileStorage fileStorage;
  private final ApplicationEventPublisher eventPublisher;

  public DocumentService(
      DocumentRepository documentRepository,
      UserRepository userRepository,
      FileValidator fileValidator,
      FileStorage fileStorage,
      ApplicationEventPublisher eventPublisher) {
    this.documentRepository = documentRepository;
    this.userRepository = userRepository;
    this.fileValidator = fileValidator;
    this.fileStorage = fileStorage;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Valide, déduplique (SHA-256), copie sur disque puis enregistre le document en {@code PENDING}.
   * Si l'insertion échoue, le fichier déjà écrit est supprimé pour ne pas laisser d'orphelin.
   */
  @Transactional
  public UploadResponse upload(MultipartFile file, String uploaderEmail) {
    ValidatedFile validated = fileValidator.validate(file);
    String sha256 = sha256(file);
    if (documentRepository.existsBySha256(sha256)) {
      throw new DuplicateDocumentException();
    }
    UUID uploaderId =
        userRepository
            .findByEmail(uploaderEmail)
            .orElseThrow(() -> new IllegalStateException("Utilisateur inconnu : " + uploaderEmail))
            .getId();

    UUID id = UUID.randomUUID();
    Path stored = fileStorage.store(inputStream(file), id, validated.extension());
    try {
      documentRepository.saveAndFlush( // flush : la contrainte UNIQUE est vérifiée ici
          new Document(
              id,
              displayName(file),
              validated.detectedType(),
              file.getSize(),
              sha256,
              stored.toString(),
              uploaderId));
    } catch (DataIntegrityViolationException e) { // deux imports identiques simultanés
      fileStorage.delete(stored);
      throw new DuplicateDocumentException();
    } catch (RuntimeException e) {
      fileStorage.delete(stored);
      throw e;
    }
    eventPublisher.publishEvent(new DocumentUploadedEvent(id));
    return new UploadResponse(id, DocumentStatus.PENDING);
  }

  @Transactional(readOnly = true)
  public List<DocumentDto> list() {
    return documentRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(DocumentDto::from)
        .toList();
  }

  @Transactional
  public void delete(UUID id) {
    Document document =
        documentRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Document introuvable : " + id));
    documentRepository.delete(document);
    documentRepository.flush(); // la ligne doit partir avant le fichier
    fileStorage.delete(fileStorage.resolve(document.getStoragePath()));
  }

  /** Calculé en flux : le fichier n'est jamais chargé entièrement en mémoire. */
  private static String sha256(MultipartFile file) {
    MessageDigest digest = sha256Digest();
    try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture du fichier importé impossible", e);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // garanti par toute JVM
    }
  }

  private static InputStream inputStream(MultipartFile file) {
    try {
      return file.getInputStream();
    } catch (IOException e) {
      throw new UncheckedIOException("Lecture du fichier importé impossible", e);
    }
  }

  /** Garde seulement le nom (sans chemin client éventuel), tronqué à la taille de la colonne. */
  private static String displayName(MultipartFile file) {
    String name = StringUtils.getFilename(StringUtils.cleanPath(file.getOriginalFilename()));
    return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
  }
}
