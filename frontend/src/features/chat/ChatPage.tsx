import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useNavigate, useParams } from 'react-router'
import type { ConversationDetail, MessageDto } from '../../api/types'
import { ErrorBanner } from '../../components/ErrorBanner'
import { Spinner } from '../../components/Spinner'
import { ChatInput } from './ChatInput'
import { ConversationList } from './ConversationList'
import { MessageList } from './MessageList'
import { CONVERSATIONS_KEY, conversationKey, useConversationQuery } from './useConversations'
import { useChatStream, type CompletedExchange } from './useChatStream'

/** Les deux messages de l'échange terminé, tels que le backend vient de les enregistrer. */
function exchangeMessages(exchange: CompletedExchange): MessageDto[] {
  const now = new Date().toISOString()
  return [
    { role: 'USER', content: exchange.question, sources: null, createdAt: now },
    {
      role: 'ASSISTANT',
      content: exchange.answer,
      sources: exchange.sources.length > 0 ? exchange.sources : null,
      createdAt: now,
    },
  ]
}

/** Route /chat/:id? : sans id, la première question ouvre une nouvelle conversation. */
export function ChatPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const stream = useChatStream({
    onDone: ({ conversationId }, exchange) => {
      // L'échange est ajouté au cache tout de suite : la bulle de streaming laisse place au
      // message enregistré sans clignotement, puis le refetch aligne sur la vérité du serveur.
      queryClient.setQueryData<ConversationDetail>(conversationKey(conversationId), (old) => ({
        id: conversationId,
        title: old?.title ?? exchange.question.slice(0, 60),
        messages: [...(old?.messages ?? []), ...exchangeMessages(exchange)],
      }))
      void queryClient.invalidateQueries({ queryKey: conversationKey(conversationId) })
      void queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY })
      if (conversationId !== id) navigate(`/chat/${conversationId}`)
    },
  })

  // Changer de conversation (ou quitter la page) abandonne la réponse en cours.
  const { cancel } = stream
  useEffect(() => () => cancel(), [id, cancel])

  // Juste après done sur /chat, la nouvelle conversation s'affiche avant la fin de la navigation.
  const viewId = id ?? stream.conversationId ?? undefined
  const conversation = useConversationQuery(viewId)
  const streaming = stream.status === 'streaming'

  return (
    <div className="grid h-[calc(100vh-8.5rem)] gap-4 md:grid-cols-[16rem_1fr]">
      <aside className="hidden overflow-y-auto rounded border border-slate-200 bg-white p-3 md:block">
        <ConversationList />
      </aside>

      <section className="flex min-h-0 flex-col rounded border border-slate-200 bg-white">
        <header className="border-b border-slate-200 px-4 py-3">
          <h1 className="sr-only">Chat</h1>
          <h2 className="truncate font-semibold text-slate-800">
            {conversation.data?.title ?? 'Nouvelle conversation'}
          </h2>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          {viewId && conversation.isPending ? (
            <Spinner />
          ) : conversation.error ? (
            <ErrorBanner message={conversation.error.message} />
          ) : (
            <MessageList
              messages={conversation.data?.messages ?? []}
              pending={stream.status === 'idle' ? null : stream}
            />
          )}
        </div>

        <div className="border-t border-slate-200 p-3">
          <ChatInput
            disabled={streaming || conversation.isError}
            onSend={(question) => void stream.send(question, viewId)}
          />
        </div>
      </section>
    </div>
  )
}
