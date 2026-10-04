package com.acme.rag.chat;

import com.acme.rag.chat.dto.ChatAnswer;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Orchestre recherche → décision de refus → appel au LLM. Version temporaire non streaming (J5) :
 * elle sera remplacée par le flux SSE au jalon J6.
 */
@Service
public class ChatService {

  private final RetrievalService retrievalService;
  private final RagPromptFactory promptFactory;
  private final ChatClient chatClient;

  /** {@code ChatClient.Builder} est auto-configuré par Spring AI autour du {@code ChatModel}. */
  public ChatService(
      RetrievalService retrievalService,
      RagPromptFactory promptFactory,
      ChatClient.Builder chatClientBuilder) {
    this.retrievalService = retrievalService;
    this.promptFactory = promptFactory;
    this.chatClient = chatClientBuilder.build();
  }

  public ChatAnswer answer(String question) {
    List<Document> chunks = retrievalService.retrieve(question);
    if (chunks.isEmpty()) {
      return ChatAnswer.refusal(); // le LLM n'est jamais appelé (AC8.1)
    }
    String content =
        chatClient
            .prompt()
            // SystemMessage déjà construit : .system(String) passerait le texte au moteur de
            // template, qui échouerait sur les accolades présentes dans un extrait.
            .messages(new SystemMessage(promptFactory.systemPrompt(chunks)))
            .user(question)
            .call()
            .content();
    return new ChatAnswer(content, promptFactory.sources(chunks));
  }
}
