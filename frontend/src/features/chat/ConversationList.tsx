import { Link, NavLink } from 'react-router'
import { ErrorBanner } from '../../components/ErrorBanner'
import { Spinner } from '../../components/Spinner'
import { useConversationsQuery } from './useConversations'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `block truncate rounded px-2 py-1.5 text-sm ${
    isActive ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-700 hover:bg-slate-100'
  }`

/** Barre latérale : les conversations de l'utilisateur, la plus récente en tête. */
export function ConversationList() {
  const { data: conversations, isPending, error } = useConversationsQuery()

  return (
    <nav aria-label="Conversations" className="space-y-3">
      <Link
        to="/chat"
        className="block rounded bg-indigo-600 px-3 py-2 text-center text-sm font-medium text-white hover:bg-indigo-700"
      >
        Nouvelle conversation
      </Link>
      {isPending && <Spinner />}
      {error && <ErrorBanner message={error.message} />}
      {conversations?.length === 0 && (
        <p className="px-2 text-sm text-slate-500">Aucune conversation.</p>
      )}
      <ul className="space-y-1">
        {conversations?.map((conversation) => (
          <li key={conversation.id}>
            <NavLink
              to={`/chat/${conversation.id}`}
              title={conversation.title}
              className={linkClass}
            >
              {conversation.title}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
