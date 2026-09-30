package com.acme.rag;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class HealthEndpointSecurityTest {

  @Autowired MockMvc mockMvc;

  @Test
  void healthIsPublicAndUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void otherEndpointsRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
  }
}
