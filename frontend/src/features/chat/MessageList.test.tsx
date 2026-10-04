import { render, screen, within } from '@testing-library/react'
import { aMessage, aSource } from '../../test/handlers'
import { MessageList } from './MessageList'
import type { ChatStreamState } from './useChatStream'

function pending(overrides: Partial<ChatStreamState> = {}): ChatStreamState {
  return {
    status: 'streaming',
    question: 'Combien de jours de télétravail ?',
    text: '',
    sources: [],
    conversationId: null,
    error: null,
    ...overrides,
  }
}

describe('MessageList (AC9.3)', () => {
  it('invite à poser une question quand la conversation est vide', () => {
    render(<MessageList messages={[]} />)

    expect(screen.getByText(/Posez une question/)).toBeInTheDocument()
  })

  it("affiche l'historique avec les sources sous les réponses", () => {
    render(
      <MessageList
        messages={[
          aMessage(),
          aMessage({ role: 'ASSISTANT', content: 'Deux jours.', sources: [aSource()] }),
        ]}
      />,
    )

    expect(screen.getByRole('article', { name: 'Question' })).toHaveTextContent(
      'Combien de jours de télétravail par semaine ?',
    )
    const answer = screen.getByRole('article', { name: "Réponse de l'assistant" })
    expect(answer).toHaveTextContent('Deux jours.')
    expect(within(answer).getByText('Sources (1)')).toBeInTheDocument()
  })

  it('affiche un état de chargement tant que le premier token n’est pas arrivé', () => {
    render(<MessageList messages={[]} pending={pending()} />)

    expect(screen.getByRole('article', { name: 'Question' })).toHaveTextContent(
      'Combien de jours de télétravail ?',
    )
    expect(screen.getByRole('status')).toHaveTextContent('Réponse en cours…')
  })

  it('affiche les tokens reçus au fil du flux', () => {
    const { rerender } = render(<MessageList messages={[]} pending={pending({ text: 'Deux ' })} />)

    expect(screen.getByRole('article', { name: "Réponse de l'assistant" })).toHaveTextContent(
      'Deux',
    )
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    rerender(<MessageList messages={[]} pending={pending({ text: 'Deux jours par semaine.' })} />)

    expect(screen.getByRole('article', { name: "Réponse de l'assistant" })).toHaveTextContent(
      'Deux jours par semaine.',
    )
  })

  it('affiche un message d’erreur sur l’événement error', () => {
    render(
      <MessageList
        messages={[]}
        pending={pending({ status: 'error', error: 'Le modèle ne répond pas' })}
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('Le modèle ne répond pas')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
