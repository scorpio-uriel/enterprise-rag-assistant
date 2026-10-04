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
