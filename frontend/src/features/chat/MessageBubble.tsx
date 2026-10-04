import type { MessageRole, SourceDto } from '../../api/types'
import { SourcesPanel } from './SourcesPanel'

interface MessageBubbleProps {
  role: MessageRole
  content: string
  sources?: SourceDto[] | null
}

/** Un message. Le texte est rendu tel quel (jamais de dangerouslySetInnerHTML, PLAN § 5.3). */
export function MessageBubble({ role, content, sources }: MessageBubbleProps) {
  const isUser = role === 'USER'
  return (
    <article
      aria-label={isUser ? 'Question' : "Réponse de l'assistant"}
      className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}
    >
      <div
        className={`max-w-[80%] rounded-lg px-4 py-2 ${
          isUser ? 'bg-indigo-600 text-white' : 'border border-slate-200 bg-white text-slate-800'
        }`}
      >
        <p className="whitespace-pre-wrap">{content}</p>
        {!isUser && sources && sources.length > 0 && <SourcesPanel sources={sources} />}
      </div>
    </article>
  )
}
