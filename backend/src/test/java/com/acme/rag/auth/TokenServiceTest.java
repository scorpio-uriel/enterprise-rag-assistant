package com.acme.rag.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

  private static final SecretKey KEY =
      new SecretKeySpec(
          "unit-test-secret-for-hs256-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
          "HmacSHA256");

  @Test
  void issuesSignedTokenWithSubjectRolesAndExpiry() {
    // Instant récent : le décodeur rejette les tokens expirés, on reste donc proche de "maintenant"
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Duration ttl = Duration.ofHours(1);
    TokenService service =
        new TokenService(
            NimbusJwtEncoder.withSecretKey(KEY).algorithm(MacAlgorithm.HS256).build(),
            new JwtProperties("ignored-here-secret-of-32-bytes-min!!", ttl),
            Clock.fixed(now, ZoneOffset.UTC));

    IssuedToken issued = service.issue("user@acme.local", Role.USER);

    Jwt jwt =
        NimbusJwtDecoder.withSecretKey(KEY)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .decode(issued.token());
    assertThat(jwt.getSubject()).isEqualTo("user@acme.local");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    assertThat(jwt.getIssuedAt()).isEqualTo(now);
    assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(ttl));
    assertThat(issued.expiresAt()).isEqualTo(now.plus(ttl));
  }
}
