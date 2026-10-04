package com.acme.rag.conversation;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.auth.SecurityConfig;
import com.acme.rag.common.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Tranche web : accès, sujet du JWT transmis au service, et 404 traduit par l'advice. */
@WebMvcTest(ConversationController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ConversationControllerSecurityTest {

  private static final String EMAIL = "user@acme.local";

  @Autowired MockMvc mockMvc;

  @MockitoBean UserDetailsService userDetailsService; // requis par l'AuthenticationManager
  @MockitoBean ConversationService conversationService;

  @Test
  void withoutTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());

    verifyNoInteractions(conversationService);
  }

  @Test
  void listsTheConversationsOfTheTokenSubject() throws Exception {
    UUID id = UUID.randomUUID();
    when(conversationService.list(EMAIL))
        .thenReturn(List.of(new ConversationSummaryDto(id, "Titre", Instant.now())));

    mockMvc
        .perform(get("/api/conversations").with(userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].title").value("Titre"));
  }

  @Test
  void foreignConversationIsNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    when(conversationService.get(id, EMAIL)).thenThrow(new NotFoundException("introuvable"));

    mockMvc
        .perform(get("/api/conversations/{id}", id).with(userJwt()))
        .andExpect(status().isNotFound());
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
    return jwt().jwt(j -> j.subject(EMAIL)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
