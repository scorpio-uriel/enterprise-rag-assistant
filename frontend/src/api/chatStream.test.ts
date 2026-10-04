import { http, HttpResponse } from 'msw'
import { aSource, server, sseResponse, SUCCESSFUL_CHAT } from '../test/handlers'
import { loginAs } from '../test/renderWithProviders'
import { streamChat, type ChatStreamHandlers } from './chatStream'
import { ApiError, sessionStore, setUnauthorizedHandler } from './client'

function spyHandlers(): ChatStreamHandlers {
  return { onToken: vi.fn(), onSources: vi.fn(), onDone: vi.fn(), onError: vi.fn() }
}

describe('streamChat', () => {
  beforeEach(() => loginAs('USER'))

  it('envoie la question avec le JWT et distribue les événements du flux', async () => {
    let authorization: string | null = null
    let body: unknown = null
    server.use(
      http.post('/api/chat', async ({ request }) => {
        authorization = request.headers.get('Authorization')
        body = await request.json()
        return sseResponse(SUCCESSFUL_CHAT)
      }),
    )
    const handlers = spyHandlers()

    await streamChat({ conversationId: 'conv-1', question: 'Télétravail ?' }, handlers)

    expect(authorization).toBe('Bearer jwt-token')
    expect(body).toEqual({ conversationId: 'conv-1', question: 'Télétravail ?' })
    expect(handlers.onToken).toHaveBeenNthCalledWith(1, 'Deux jours ')
    expect(handlers.onToken).toHaveBeenNthCalledWith(2, 'par semaine.')
    expect(handlers.onSources).toHaveBeenCalledWith([aSource()])
    expect(handlers.onDone).toHaveBeenCalledWith({ conversationId: 'conv-1', messageId: 'msg-2' })
    expect(handlers.onError).not.toHaveBeenCalled()
  })

  it("transmet le message de l'événement error", async () => {
    server.use(
      http.post('/api/chat', () =>
        sseResponse([
          { event: 'token', data: { text: 'Deux' } },
          { event: 'error', data: { message: 'Le modèle ne répond pas' } },
        ]),
      ),
    )
    const handlers = spyHandlers()

    await streamChat({ question: 'Télétravail ?' }, handlers)

    expect(handlers.onError).toHaveBeenCalledWith('Le modèle ne répond pas')
    expect(handlers.onDone).not.toHaveBeenCalled()
  })

  it('rejette sans réessayer quand le serveur échoue (la question ne part qu’une fois)', async () => {
    const calls = vi.fn()
    server.use(
      http.post('/api/chat', () => {
        calls()
        return HttpResponse.json({ detail: 'Erreur interne' }, { status: 500 })
      }),
    )

    await expect(streamChat({ question: 'Télétravail ?' }, spyHandlers())).rejects.toEqual(
      new ApiError(500, 'Erreur interne'),
    )
    // La bibliothèque réessaierait après 1 s : on attend plus longtemps pour le vérifier.
    await new Promise((resolve) => setTimeout(resolve, 1200))
    expect(calls).toHaveBeenCalledOnce()
  })

  it('traduit le 404 d’une conversation inaccessible en ApiError lisible', async () => {
    server.use(
      http.post('/api/chat', () =>
        HttpResponse.json({ detail: 'Conversation introuvable' }, { status: 404 }),
      ),
    )

    await expect(
      streamChat({ conversationId: 'autre', question: 'Q ?' }, spyHandlers()),
    ).rejects.toMatchObject({ status: 404, detail: 'Conversation introuvable' })
  })

  it('vide la session et prévient l’AuthProvider sur un 401', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    server.use(http.post('/api/chat', () => new HttpResponse(null, { status: 401 })))

    await expect(streamChat({ question: 'Q ?' }, spyHandlers())).rejects.toBeInstanceOf(ApiError)

    expect(sessionStore.get()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledOnce()
    setUnauthorizedHandler(() => {})
  })
})
