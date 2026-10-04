import { useQuery } from '@tanstack/react-query'
import { getConversation, listConversations } from '../../api/conversations'

export const CONVERSATIONS_KEY = ['conversations']

export function conversationKey(id: string) {
  return ['conversation', id]
}

/** Conversations de l'utilisateur connecté, pour la barre latérale. */
export function useConversationsQuery() {
  return useQuery({ queryKey: CONVERSATIONS_KEY, queryFn: listConversations })
}

/** Détail d'une conversation (messages et sources), rechargé après un F5 (AC7.4). */
export function useConversationQuery(id: string | undefined) {
  return useQuery({
    queryKey: conversationKey(id ?? ''),
    queryFn: () => getConversation(id!),
    enabled: id !== undefined,
  })
}
