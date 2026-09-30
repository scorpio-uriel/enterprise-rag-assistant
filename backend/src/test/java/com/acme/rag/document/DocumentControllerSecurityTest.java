package com.acme.rag.document;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.auth.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tranche web : seuls le contrôleur et la sécurité sont chargés (pas de base). {@code jwt()} simule
 * un token déjà validé avec les autorités voulues ; le service est remplacé par un mock.
 */
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class DocumentControllerSecurityTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean UserDetailsService userDetailsService; // requis par l'AuthenticationManager
  @MockitoBean DocumentService documentService;

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
  void userRoleCannotUpload() throws Exception { // AC2.1
    mockMvc
        .perform(
            multipart("/api/documents")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "texte".getBytes()))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isForbidden());

    Mockito.verifyNoInteractions(documentService);
  }

  @Test
  void adminRoleIsAllowed() throws Exception {
    when(documentService.list()).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/documents").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
