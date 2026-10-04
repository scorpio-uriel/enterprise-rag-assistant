package com.acme.rag.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.acme.rag.TestAiConfig;
import com.acme.rag.chat.dto.ChatAnswer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

class ChatServiceTest {

  private final RetrievalService retrievalService = mock(RetrievalService.class);
  private final ChatModel chatModel =
      mock(ChatModel.class, withSettings().defaultAnswer(TestAiConfig::withDefaultOptions));
  private final ChatService chatService =
      new ChatService(retrievalService, new RagPromptFactory(), ChatClient.builder(chatModel));

  @Test
  void refusesWithoutCallingTheModelWhenNothingIsRelevant() { // AC8.1
    when(retrievalService.retrieve(any())).thenReturn(List.of());

    ChatAnswer answer = chatService.answer("Quelle est la capitale du Pérou ?");

    assertThat(answer.answer()).isEqualTo(RagPromptFactory.REFUSAL);
    assertThat(answer.sources()).isEmpty();
    verifyNoInteractions(chatModel);
  }

  @Test
  void answersFromTheModelWithSources() {
    when(retrievalService.retrieve(any()))
        .thenReturn(
            List.of(
                Document.builder()
                    .text("Le télétravail est autorisé deux jours par semaine.")
                    .metadata(Map.of("fileName", "teletravail.pdf", "page", 2))
                    .score(0.8)
                    .build()));
    when(chatModel.call(any(Prompt.class))).thenReturn(response("Deux jours par semaine."));

    ChatAnswer answer = chatService.answer("Combien de jours de télétravail ?");

    assertThat(answer.answer()).isEqualTo("Deux jours par semaine.");
    assertThat(answer.sources()).singleElement().satisfies(s -> assertThat(s.page()).isEqualTo(2));
  }

  @Test
  void bracesInExcerptsOrQuestionDoNotBreakThePrompt() { // pas d'interprétation comme template
    when(retrievalService.retrieve(any()))
        .thenReturn(List.of(new Document("Config : {\"mode\": \"strict\"}", Map.of())));
    when(chatModel.call(any(Prompt.class))).thenReturn(response("ok"));

    chatService.answer("Que vaut {mode} ?");

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    assertThat(prompt.getValue().getSystemMessage().getText()).contains("{\"mode\": \"strict\"}");
    assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("Que vaut {mode} ?");
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
