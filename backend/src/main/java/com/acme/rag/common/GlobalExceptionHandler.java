package com.acme.rag.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduit les exceptions levées par les contrôleurs en JSON RFC 9457 ({@code ProblemDetail}). La
 * classe parente gère déjà les exceptions standard de Spring MVC (dont {@code @Valid} → 400).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Message volontairement identique pour un email inconnu et un mauvais mot de passe (AC1.2). */
  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail handleAuthentication(AuthenticationException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Email ou mot de passe incorrect");
    problem.setTitle("Authentification échouée");
    return problem;
  }
}
