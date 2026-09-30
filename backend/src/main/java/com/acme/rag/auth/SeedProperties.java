package com.acme.rag.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Mots de passe des comptes de démo, lus depuis l'environnement (ADMIN_PASSWORD, USER_PASSWORD).
 */
@Validated
@ConfigurationProperties(prefix = "security.seed")
public record SeedProperties(@NotBlank String adminPassword, @NotBlank String userPassword) {}
