import { useEffect, useRef } from 'react'
import type { MessageDto } from '../../api/types'
import { ErrorBanner } from '../../components/ErrorBanner'
import { Spinner } from '../../components/Spinner'
import { MessageBubble } from './MessageBubble'
import type { ChatStreamState } from './useChatStream'

interface MessageListProps {
  /** Messages déjà enregistrés de la conversation. */
  messages: MessageDto[]
  /** Échange en cours (ou en échec), affiché après l'historique. */
  pending?: ChatStreamState | null
}

export function MessageList({ messages, pending }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null)

  // Suit la réponse à mesure qu'elle s'allonge. jsdom n'implémente pas scrollIntoView.
  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ block: 'end' })
  }, [messages, pending?.text, pending?.status])

  if (messages.length === 0 && !pending) {
    return (
      <p className="mt-8 text-center text-slate-500">
        Posez une question sur les documents de l&apos;entreprise.
      </p>
    )
  }

  return (
    <div className="space-y-4" aria-busy={pending?.status === 'streaming'}>
      {messages.map((message, index) => (
        <MessageBubble
          key={index}
          role={message.role}
          content={message.content}
          sources={message.sources}
        />
      ))}
      {pending && (
        <>
          <MessageBubble role="USER" content={pending.question} />
          {pending.text !== '' && (
            <MessageBubble role="ASSISTANT" content={pending.text} sources={pending.sources} />
          )}
          {pending.status === 'streaming' && pending.text === '' && (
            <Spinner label="Réponse en cours…" />
          )}
          {pending.status === 'error' && pending.error && <ErrorBanner message={pending.error} />}
        </>
      )}
      <div ref={bottomRef} />
    </div>
  )
}
