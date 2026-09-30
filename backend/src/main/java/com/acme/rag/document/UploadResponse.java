package com.acme.rag.document;

import java.util.UUID;

public record UploadResponse(UUID id, DocumentStatus status) {}
