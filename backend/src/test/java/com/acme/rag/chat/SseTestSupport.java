package com.acme.rag.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Exécute une requête SSE avec MockMvc et découpe le corps reçu en événements. Le contrôleur
 * renvoie un {@code Flux} : la première requête ne fait que démarrer le traitement asynchrone ;
 * {@code asyncDispatch} rejoue ensuite le dispatch {@code ASYNC} (filtres de sécurité compris) une
 * fois le flux terminé.
 */
public final class SseTestSupport {

  /** Un événement SSE : {@code event:<name>} puis {@code data:<data>} (JSON). */
  public record SseEvent(String name, String data) {}

  private SseTestSupport() {}

  public static MvcResult perform(MockMvc mockMvc, MockHttpServletRequestBuilder request)
      throws Exception {
    MvcResult started =
        mockMvc
            .perform(request)
            .andExpect(MockMvcResultMatchers.request().asyncStarted())
            .andReturn();
    return mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started)).andReturn();
  }

  /** Les événements sont séparés par une ligne vide ; chaque ligne est {@code champ:valeur}. */
  public static List<SseEvent> parse(String body) {
    List<SseEvent> events = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String name = null;
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring("event:".length()).strip();
        } else if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()));
        }
      }
      if (name != null) {
        events.add(new SseEvent(name, data.toString()));
      }
    }
    return events;
  }
}
