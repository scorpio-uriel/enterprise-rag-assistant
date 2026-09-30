package com.acme.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import({TestcontainersConfiguration.class, TestAiConfig.class})
@SpringBootTest
@ActiveProfiles("test")
class RagApplicationTests {

  @Test
  void contextLoads() {}
}
