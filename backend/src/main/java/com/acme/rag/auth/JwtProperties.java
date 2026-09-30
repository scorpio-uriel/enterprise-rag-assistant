package com.acme.rag.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Réglages JWT typés : l'application refuse de démarrer si le secret fait moins de 32 octets. */
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(@NotBlank @Size(min = 32) String secret, @NotNull Duration ttl) {}
