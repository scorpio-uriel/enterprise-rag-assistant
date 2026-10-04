package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.rag.auth.SecurityConfig;
import com.acme.rag.chat.SseTestSupport.SseEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * Tranche web du chat : accès, validation du corps, et flux SSE complet à travers la sécurité (le
 * dispatch {@code ASYNC} rejoué par {@code asyncDispatch} doit être autorisé).
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ChatControllerSecurityTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean UserDetailsService userDetailsService; // requis par l'AuthenticationManager
  @MockitoBean ChatService chatService;

  @Test
  void withoutTokenIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Bonjour ?\"}"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(chatService);
  }

  @Test
  void blankQuestionIsRejected() throws Exception {
    postAsUser("{\"question\":\"  \"}");
  }

  @Test
  void tooLongQuestionIsRejected() throws Exception {
    postAsUser("{\"question\":\"" + "a".repeat(1001) + "\"}");
  }

  @Test
  void userReceivesTheEventStream() throws Exception {
    when(chatService.ask(any()))
        .thenReturn(
            Flux.just(
                ChatEvents.token("Bon"),
                ChatEvents.token("jour"),
                ChatEvents.done(UUID.randomUUID(), UUID.randomUUID())));

    MvcResult result =
        SseTestSupport.perform(
            mockMvc,
            post("/api/chat")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Bonjour ?\"}"));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    assertThat(SseTestSupport.parse(result.getResponse().getContentAsString()))
        .extracting(SseEvent::name)
        .containsExactly(ChatEvents.TOKEN, ChatEvents.TOKEN, ChatEvents.DONE);
  }

  private void postAsUser(String body) throws Exception {
    mockMvc
        .perform(
            post("/api/chat")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(chatService);
  }
}
