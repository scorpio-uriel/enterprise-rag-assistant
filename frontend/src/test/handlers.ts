import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type {
  ConversationDetail,
  ConversationSummary,
  DocumentDto,
  MessageDto,
  SourceDto,
} from '../api/types'

export function aDocument(overrides: Partial<DocumentDto> = {}): DocumentDto {
  return {
    id: 'doc-1',
    fileName: 'politique-teletravail.pdf',
    contentType: 'application/pdf',
    sizeBytes: 204800,
    status: 'INDEXED',
    errorMessage: null,
    chunkCount: 12,
    createdAt: '2026-10-01T09:00:00Z',
    ...overrides,
  }
}

export function aSource(overrides: Partial<SourceDto> = {}): SourceDto {
  return {
    fileName: 'politique-teletravail.pdf',
    page: 3,
    excerpt: 'Les salariés peuvent télétravailler 2 jours par semaine.',
    score: 0.87,
    ...overrides,
  }
}

export function aMessage(overrides: Partial<MessageDto> = {}): MessageDto {
  return {
    role: 'USER',
    content: 'Combien de jours de télétravail par semaine ?',
    sources: null,
    createdAt: '2026-10-01T09:00:00Z',
    ...overrides,
  }
}

export function aConversationSummary(
  overrides: Partial<ConversationSummary> = {},
): ConversationSummary {
  return {
    id: 'conv-1',
    title: 'Combien de jours de télétravail par semaine ?',
    updatedAt: '2026-10-01T09:00:00Z',
    ...overrides,
  }
}

export function aConversation(overrides: Partial<ConversationDetail> = {}): ConversationDetail {
  return {
    id: 'conv-1',
    title: 'Combien de jours de télétravail par semaine ?',
    messages: [
      aMessage(),
      aMessage({ role: 'ASSISTANT', content: 'Deux jours par semaine.', sources: [aSource()] }),
    ],
    ...overrides,
  }
}

export interface SseEvent {
  event: string
  data: unknown
}

/** Un événement au format SSE, tel que l'écrit Spring : event:<nom>, data:<json>, ligne vide. */
export function sseChunk({ event, data }: SseEvent): string {
  return `event:${event}\ndata:${JSON.stringify(data)}\n\n`
}

/** Réponse text/event-stream qui émet les événements donnés puis se ferme. */
export function sseResponse(events: SseEvent[]): HttpResponse<ReadableStream> {
  const encoder = new TextEncoder()
  const body = new ReadableStream({
    start(controller) {
      for (const event of events) controller.enqueue(encoder.encode(sseChunk(event)))
      controller.close()
    },
  })
  return new HttpResponse(body, { headers: { 'Content-Type': 'text/event-stream' } })
}

/** Échange réussi : deux tokens, les sources, puis done. */
export const SUCCESSFUL_CHAT: SseEvent[] = [
  { event: 'token', data: { text: 'Deux jours ' } },
  { event: 'token', data: { text: 'par semaine.' } },
  { event: 'sources', data: [aSource()] },
  { event: 'done', data: { conversationId: 'conv-1', messageId: 'msg-2' } },
]

/** Fausse API par défaut ; chaque test peut la surcharger avec server.use(...). */
export const handlers = [
  http.post('/api/auth/login', async ({ request }) => {
    const { email, password } = (await request.json()) as { email: string; password: string }
    if (password !== 'secret') {
      return HttpResponse.json(
        {
          title: 'Authentification échouée',
          detail: 'Email ou mot de passe incorrect',
          status: 401,
        },
        { status: 401 },
      )
    }
    return HttpResponse.json({
      token: 'jwt-token',
      role: email.startsWith('admin') ? 'ADMIN' : 'USER',
      expiresAt: '2999-01-01T00:00:00Z',
    })
  }),
  http.get('/api/documents', () => HttpResponse.json([aDocument()])),
  http.post('/api/documents', () =>
    HttpResponse.json({ id: 'doc-2', status: 'PENDING' }, { status: 202 }),
  ),
  http.delete('/api/documents/:id', () => new HttpResponse(null, { status: 204 })),
  http.get('/api/conversations', () => HttpResponse.json([])),
  http.get('/api/conversations/:id', () => HttpResponse.json(aConversation())),
  http.post('/api/chat', () => sseResponse(SUCCESSFUL_CHAT)),
]

export const server = setupServer(...handlers)
