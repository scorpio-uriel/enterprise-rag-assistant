// Miroirs TypeScript des DTO du backend (records Java). À garder synchronisés.

export type Role = 'ADMIN' | 'USER'

export interface LoginResponse {
  token: string
  role: Role
  expiresAt: string
}

export type DocumentStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED'

export interface DocumentDto {
  id: string
  fileName: string
  contentType: string
  sizeBytes: number
  status: DocumentStatus
  errorMessage: string | null
  chunkCount: number
  createdAt: string
}

export interface UploadResponse {
  id: string
  status: DocumentStatus
}

/** Corps d'erreur RFC 9457 renvoyé par GlobalExceptionHandler. */
export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
}

export type MessageRole = 'USER' | 'ASSISTANT'

/** Extrait cité à l'appui d'une réponse. page est null pour les formats sans pagination. */
export interface SourceDto {
  fileName: string
  page: number | null
  excerpt: string
  score: number
}

export interface MessageDto {
  role: MessageRole
  content: string
  sources: SourceDto[] | null
  createdAt: string
}

export interface ConversationSummary {
  id: string
  title: string
  updatedAt: string
}

export interface ConversationDetail {
  id: string
  title: string
  messages: MessageDto[]
}

/** Corps de POST /api/chat : sans conversationId, le backend ouvre une nouvelle conversation. */
export interface ChatRequest {
  conversationId?: string
  question: string
}

/** Données de l'événement SSE done, toujours le dernier d'un flux réussi. */
export interface DoneEvent {
  conversationId: string
  messageId: string
}
