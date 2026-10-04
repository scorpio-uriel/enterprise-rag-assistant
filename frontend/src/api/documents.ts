import { apiFetch } from './client'
import type { DocumentDto, UploadResponse } from './types'

export function listDocuments(): Promise<DocumentDto[]> {
  return apiFetch<DocumentDto[]>('/api/documents')
}

export function uploadDocument(file: File): Promise<UploadResponse> {
  const body = new FormData()
  body.append('file', file) // nom du @RequestParam côté DocumentController
  return apiFetch<UploadResponse>('/api/documents', { method: 'POST', body })
}

export function deleteDocument(id: string): Promise<void> {
  return apiFetch<void>(`/api/documents/${id}`, { method: 'DELETE' })
}
