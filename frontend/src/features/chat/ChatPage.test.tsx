import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { ConversationSummary } from '../../api/types'
import {
  aConversation,
  aConversationSummary,
  server,
  sseChunk,
  SUCCESSFUL_CHAT,
  sseResponse,
} from '../../test/handlers'
import { loginAs, renderApp } from '../../test/renderWithProviders'

describe('ChatPage', () => {
  beforeEach(() => loginAs('USER'))

  it('ouvre une conversation à la première question puis navigue vers elle', async () => {
    let conversations: ConversationSummary[] = []
    let chatBody: unknown = null
    server.use(
      http.get('/api/conversations', () => HttpResponse.json(conversations)),
      http.post('/api/chat', async ({ request }) => {
        chatBody = await request.json()
        conversations = [aConversationSummary()]
        return sseResponse(SUCCESSFUL_CHAT)
      }),
    )
    const { router } = renderApp('/chat')
    expect(await screen.findByText('Aucune conversation.')).toBeInTheDocument()

    await userEvent.type(
      screen.getByLabelText('Votre question'),
      'Combien de jours de télétravail par semaine ?{Enter}',
    )

    const answer = await screen.findByRole('article', { name: "Réponse de l'assistant" })
    expect(answer).toHaveTextContent('Deux jours par semaine.')
    expect(within(answer).getByText('Sources (1)')).toBeInTheDocument()
    expect(chatBody).toEqual({ question: 'Combien de jours de télétravail par semaine ?' })
    await waitFor(() => expect(router.state.location.pathname).toBe('/chat/conv-1'))
    // La liste des conversations est invalidée : la nouvelle apparaît dans la barre latérale.
    expect(
      await screen.findByRole('link', { name: 'Combien de jours de télétravail par semaine ?' }),
    ).toBeInTheDocument()
  })

  // AC7.4 (version automatisée) : un F5 sur /chat/{id} recharge messages et sources.
  it('recharge une conversation existante avec ses sources, et permet de la poursuivre', async () => {
    let chatBody: unknown = null
    server.use(
      http.get('/api/conversations', () => HttpResponse.json([aConversationSummary()])),
      http.post('/api/chat', async ({ request }) => {
        chatBody = await request.json()
        return sseResponse(SUCCESSFUL_CHAT)
      }),
    )
    renderApp('/chat/conv-1')

    const answer = await screen.findByRole('article', { name: "Réponse de l'assistant" })
    expect(answer).toHaveTextContent('Deux jours par semaine.')
    expect(within(answer).getByText('Sources (1)')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: aConversation().title })).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Votre question'), 'Et pour les cadres ?{Enter}')

    await waitFor(() =>
      expect(chatBody).toEqual({ conversationId: 'conv-1', question: 'Et pour les cadres ?' }),
    )
  })

  it('affiche la réponse progressivement et bloque la saisie pendant le flux', async () => {
    const encoder = new TextEncoder()
    let release!: () => void
    server.use(
      http.post('/api/chat', () => {
        const body = new ReadableStream({
          async start(controller) {
            controller.enqueue(encoder.encode(sseChunk(SUCCESSFUL_CHAT[0])))
            await new Promise<void>((resolve) => (release = resolve))
            for (const event of SUCCESSFUL_CHAT.slice(1)) {
              controller.enqueue(encoder.encode(sseChunk(event)))
            }
            controller.close()
          },
        })
        return new HttpResponse(body, { headers: { 'Content-Type': 'text/event-stream' } })
      }),
    )
    renderApp('/chat')

    await userEvent.type(await screen.findByLabelText('Votre question'), 'Télétravail ?{Enter}')

    // Premier token affiché alors que le flux n'est pas terminé (AC5.3).
    const answer = await screen.findByRole('article', { name: "Réponse de l'assistant" })
    expect(answer).toHaveTextContent('Deux jours')
    expect(answer).not.toHaveTextContent('par semaine.')
    expect(screen.getByLabelText('Votre question')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeDisabled()

    release()

    await waitFor(() => expect(screen.getByLabelText('Votre question')).toBeEnabled())
    expect(screen.getByRole('article', { name: "Réponse de l'assistant" })).toHaveTextContent(
      'Deux jours par semaine.',
    )
  })

  it("affiche l'erreur d'un flux en échec et réactive la saisie", async () => {
    server.use(
      http.post('/api/chat', () =>
        sseResponse([{ event: 'error', data: { message: 'Le modèle ne répond pas' } }]),
      ),
    )
    renderApp('/chat')

    await userEvent.type(await screen.findByLabelText('Votre question'), 'Télétravail ?{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent('Le modèle ne répond pas')
    expect(screen.getByLabelText('Votre question')).toBeEnabled()
  })

  it("affiche une erreur pour la conversation d'un autre utilisateur (404)", async () => {
    server.use(
      http.get('/api/conversations/:id', () =>
        HttpResponse.json({ detail: 'Conversation introuvable' }, { status: 404 }),
      ),
    )
    renderApp('/chat/autre')

    expect(await screen.findByRole('alert')).toHaveTextContent('Conversation introuvable')
    expect(screen.getByLabelText('Votre question')).toBeDisabled()
  })

  it("Maj+Entrée insère un saut de ligne au lieu d'envoyer", async () => {
    const chat = vi.fn()
    server.use(
      http.post('/api/chat', () => {
        chat()
        return sseResponse(SUCCESSFUL_CHAT)
      }),
    )
    renderApp('/chat')
    const input = await screen.findByLabelText('Votre question')

    await userEvent.type(input, 'Ligne 1{Shift>}{Enter}{/Shift}Ligne 2')

    expect(input).toHaveValue('Ligne 1\nLigne 2')
    expect(chat).not.toHaveBeenCalled()
  })
})
