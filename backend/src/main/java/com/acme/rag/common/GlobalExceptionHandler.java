package com.acme.rag.common;

import com.acme.rag.document.DuplicateDocumentException;
import com.acme.rag.document.InvalidFileException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduit les exceptions levées par les contrôleurs en JSON RFC 9457 ({@code ProblemDetail}). La
 * classe parente gère déjà les exceptions standard de Spring MVC (dont {@code @Valid} → 400).
 */
@RestControllerAdvice
@EnableConfigurationProperties(RagProperties.class) // chargé aussi dans les tranches @WebMvcTest
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private final RagProperties ragProperties;

  public GlobalExceptionHandler(RagProperties ragProperties) {
    this.ragProperties = ragProperties;
  }

  /** Message volontairement identique pour un email inconnu et un mauvais mot de passe (AC1.2). */
  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail handleAuthentication(AuthenticationException ex) {
    return problem(
        HttpStatus.UNAUTHORIZED, "Authentification échouée", "Email ou mot de passe incorrect");
  }

  @ExceptionHandler(InvalidFileException.class)
  ProblemDetail handleInvalidFile(InvalidFileException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Fichier invalide", ex.getMessage());
  }

  @ExceptionHandler(DuplicateDocumentException.class)
  ProblemDetail handleDuplicate(DuplicateDocumentException ex) {
    return problem(HttpStatus.CONFLICT, "Document en double", ex.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail handleNotFound(NotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Ressource introuvable", ex.getMessage());
  }

  /**
   * Levée par Tomcat au-delà de {@code spring.servlet.multipart.max-file-size}. La classe parente
   * répond 413 ; la SPEC exige 400 (AC2.3). On surcharge sa méthode plutôt que d'ajouter un
   * {@code @ExceptionHandler}, qui serait ambigu avec le sien.
   */
  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail body =
        problem(
            HttpStatus.BAD_REQUEST,
            "Fichier invalide",
            "Le fichier dépasse la taille maximale de "
                + ragProperties.maxFileSize().toMegabytes()
                + " Mo");
    return ResponseEntity.badRequest().body(body);
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
