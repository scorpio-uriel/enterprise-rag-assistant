package com.acme.rag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.TestAiConfig;
import com.acme.rag.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

  // valeurs de src/test/resources/application-test.yml
  private static final String USER_PASSWORD = "user-test-password";
  private static final String ADMIN_PASSWORD = "admin-test-password";

  @Autowired MockMvc mockMvc;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired JwtEncoder jwtEncoder;
  @Autowired UserRepository userRepository;

  @Test
  void userLoginReturnsJwtWithUserRole() throws Exception { // AC1.1
    String token = login("user@acme.local", USER_PASSWORD);

    assertThat(jwtDecoder.decode(token).getClaimAsStringList("roles")).containsExactly("USER");
  }

  @Test
  void adminLoginReturnsJwtWithAdminRole() throws Exception {
    mockMvc
        .perform(loginRequest("admin@acme.local", ADMIN_PASSWORD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test
  void wrongPasswordAndUnknownEmailGiveSameUnauthorizedResponse() throws Exception { // AC1.2
    String wrongPassword =
        mockMvc
            .perform(loginRequest("user@acme.local", "not-the-password"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Email ou mot de passe incorrect"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String unknownEmail =
        mockMvc
            .perform(loginRequest("nobody@acme.local", "not-the-password"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(unknownEmail).isEqualTo(wrongPassword);
  }

  @Test
  void invalidLoginBodyGivesBadRequestProblemDetail() throws Exception {
    mockMvc
        .perform(loginRequest("", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void meReturnsCurrentUserWithValidToken() throws Exception {
    String token = login("user@acme.local", USER_PASSWORD);

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("user@acme.local"))
        .andExpect(jsonPath("$.role").value("USER"));
  }

  @Test
  void protectedRouteWithoutTokenIsUnauthorized() throws Exception { // AC1.3
    mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsUnauthorized() throws Exception { // AC1.3
    // Au-delà des 60 s de tolérance d'horloge du JwtTimestampValidator
    Instant past = Instant.now().minus(10, ChronoUnit.MINUTES);
    String expired = encode(jwtEncoder, past, past.plus(5, ChronoUnit.MINUTES));

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tamperedSignatureIsUnauthorized() throws Exception { // AC1.3
    String token = login("user@acme.local", USER_PASSWORD);
    char last = token.charAt(token.length() - 2); // avant-dernier : évite les bits de padding
    String tampered =
        token.substring(0, token.length() - 2)
            + (last == 'A' ? 'B' : 'A')
            + token.charAt(token.length() - 1);

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenSignedWithAnotherSecretIsUnauthorized() throws Exception { // AC1.3
    JwtEncoder foreign =
        NimbusJwtEncoder.withSecretKey(
                new SecretKeySpec(
                    "another-secret-that-is-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"))
            .algorithm(MacAlgorithm.HS256)
            .build();
    Instant now = Instant.now();
    String forged = encode(foreign, now, now.plus(1, ChronoUnit.HOURS));

    mockMvc
        .perform(get("/api/me").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void storedPasswordIsBcryptHash() { // AC1.4, vérifié en base
    User user = userRepository.findByEmail("user@acme.local").orElseThrow();

    assertThat(user.getPasswordHash()).matches("^\\$2[ab]\\$.*").isNotEqualTo(USER_PASSWORD);
  }

  private String login(String email, String password) throws Exception {
    String body =
        mockMvc
            .perform(loginRequest(email, password))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private static org.springframework.test.web.servlet.RequestBuilder loginRequest(
      String email, String password) {
    return post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
  }

  private static String encode(JwtEncoder encoder, Instant issuedAt, Instant expiresAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject("user@acme.local")
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("roles", List.of("USER"))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
