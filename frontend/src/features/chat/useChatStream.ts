import { useCallback, useEffect, useReducer, useRef } from 'react'
import { streamChat } from '../../api/chatStream'
import { ApiError } from '../../api/client'
import type { DoneEvent, SourceDto } from '../../api/types'

export type ChatStreamStatus = 'idle' | 'streaming' | 'error'

export interface ChatStreamState {
  status: ChatStreamStatus
  /** Question en cours (ou en échec), affichée avant que le backend ne l'ait enregistrée. */
  question: string
  /** Réponse accumulée au fil des événements token. */
  text: string
  sources: SourceDto[]
  /** Renseigné par l'événement done : la conversation (éventuellement nouvelle) de l'échange. */
  conversationId: string | null
  error: string | null
}

/** Échange terminé, transmis à onDone pour l'afficher sans attendre un rechargement. */
export interface CompletedExchange {
  question: string
  answer: string
  sources: SourceDto[]
}

type Action =
  | { type: 'start'; question: string }
  | { type: 'token'; text: string }
  | { type: 'sources'; sources: SourceDto[] }
  | { type: 'done'; conversationId: string }
  | { type: 'error'; message: string }
  | { type: 'reset' }

const INITIAL_STATE: ChatStreamState = {
  status: 'idle',
  question: '',
  text: '',
  sources: [],
  conversationId: null,
  error: null,
}

function reducer(state: ChatStreamState, action: Action): ChatStreamState {
  switch (action.type) {
    case 'start':
      return { ...INITIAL_STATE, status: 'streaming', question: action.question }
    case 'token':
      return { ...state, text: state.text + action.text }
    case 'sources':
      return { ...state, sources: action.sources }
    case 'done':
      return { ...state, status: 'idle', conversationId: action.conversationId }
    case 'error':
      return { ...state, status: 'error', error: action.message }
    case 'reset':
      return INITIAL_STATE
  }
}

const NETWORK_ERROR = 'Impossible de joindre le serveur. Réessayez.'
const INTERRUPTED = 'La réponse a été interrompue. Réessayez.'

interface UseChatStreamOptions {
  /** Appelé juste après l'événement done, une fois l'échange enregistré côté backend. */
  onDone?: (done: DoneEvent, exchange: CompletedExchange) => void
}

/**
 * Pose une question à POST /api/chat et expose la réponse au fil du flux SSE.
 * Le hook ignore le routeur et TanStack Query : ChatPage réagit via onDone.
 */
export function useChatStream(options: UseChatStreamOptions = {}) {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE)
  const controllerRef = useRef<AbortController | null>(null)
  // Les callbacks du flux survivent aux re-rendus : on y lit toujours la dernière version.
  const optionsRef = useRef(options)
  useEffect(() => {
    optionsRef.current = options
  })

  const send = useCallback(async (question: string, conversationId?: string) => {
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    dispatch({ type: 'start', question })

    // Copie hors de l'état React, pour la transmettre à onDone sans attendre un rendu.
    const exchange: CompletedExchange = { question, answer: '', sources: [] }
    let finished = false

    try {
      await streamChat(
        { conversationId, question },
        {
          onToken: (text) => {
            exchange.answer += text
            dispatch({ type: 'token', text })
          },
          onSources: (sources) => {
            exchange.sources = sources
            dispatch({ type: 'sources', sources })
          },
          onDone: (done) => {
            finished = true
            dispatch({ type: 'done', conversationId: done.conversationId })
            optionsRef.current.onDone?.(done, exchange)
          },
          onError: (message) => {
            finished = true
            dispatch({ type: 'error', message })
          },
        },
        controller.signal,
      )
      // Flux fermé sans done ni error : la connexion a été coupée en route.
      if (!finished && !controller.signal.aborted) dispatch({ type: 'error', message: INTERRUPTED })
    } catch (error) {
      if (controller.signal.aborted) return
      dispatch({ type: 'error', message: error instanceof ApiError ? error.detail : NETWORK_ERROR })
    }
  }, [])

  const reset = useCallback(() => dispatch({ type: 'reset' }), [])

  /** Abandonne le flux en cours (changement de conversation) et revient à l'état initial. */
  const cancel = useCallback(() => {
    controllerRef.current?.abort()
    controllerRef.current = null
    dispatch({ type: 'reset' })
  }, [])

  // Le flux ne doit pas survivre au composant.
  useEffect(() => () => controllerRef.current?.abort(), [])

  return { ...state, send, reset, cancel }
}
