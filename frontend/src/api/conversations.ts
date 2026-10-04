import { apiFetch } from './client'
import type { ConversationDetail, ConversationSummary } from './types'

export function listConversations(): Promise<ConversationSummary[]> {
  return apiFetch<ConversationSummary[]>('/api/conversations')
}

export function getConversation(id: string): Promise<ConversationDetail> {
  return apiFetch<ConversationDetail>(`/api/conversations/${id}`)
}
