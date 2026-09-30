package com.acme.rag.document;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tranche web : seuls le contrôleur et la sécurité sont chargés (pas de base). {@code jwt()} simule
 * un token déjà validé avec les autorités voulues.
 */
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class DocumentControllerSecurityTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean UserDetailsService userDetailsService; // requis par l'AuthenticationManager

  @Test
  void withoutTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
  }

  @Test
  void userRoleIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/documents").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminRoleIsAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/documents").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
