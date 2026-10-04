package com.acme.rag.conversation;

import com.acme.rag.chat.dto.SourceDto;
import java.time.Instant;
import java.util.List;

public record MessageDto(
    MessageRole role, String content, List<SourceDto> sources, Instant createdAt) {

  static MessageDto from(Message message) {
    return new MessageDto(
        message.getRole(), message.getContent(), message.getSources(), message.getCreatedAt());
  }
}
