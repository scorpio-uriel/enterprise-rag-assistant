import { act, renderHook, waitFor } from '@testing-library/react'
import { streamChat, type ChatStreamHandlers } from '../../api/chatStream'
import { ApiError } from '../../api/client'
import { aSource } from '../../test/handlers'
import { useChatStream } from './useChatStream'

// On remplace le transport SSE pour piloter les événements un par un, dans act().
vi.mock('../../api/chatStream', () => ({ streamChat: vi.fn() }))

/** Démarre un flux factice et rend la main sur ses callbacks et sa fin. */
function controlledStream() {
  let handlers!: ChatStreamHandlers
  let signal: AbortSignal | undefined
  let finish!: () => void
  let fail!: (error: unknown) => void
  vi.mocked(streamChat).mockImplementation((_request, h, s) => {
    handlers = h
    signal = s
    return new Promise<void>((resolve, reject) => {
      finish = resolve
      fail = reject
    })
  })
  return {
    emit: (fn: (h: ChatStreamHandlers) => void) => act(() => fn(handlers)),
    finish: () => act(async () => finish()),
    fail: (error: unknown) => act(async () => fail(error)),
    signal: () => signal,
  }
}

describe('useChatStream (AC9.3)', () => {
  it('passe en streaming et accumule les tokens reçus', async () => {
    const stream = controlledStream()
    const { result } = renderHook(() => useChatStream())
    expect(result.current.status).toBe('idle')

    act(() => void result.current.send('Télétravail ?', 'conv-1'))

    expect(result.current.status).toBe('streaming')
    expect(result.current.question).toBe('Télétravail ?')
    expect(streamChat).toHaveBeenCalledWith(
      { conversationId: 'conv-1', question: 'Télétravail ?' },
      expect.anything(),
      expect.any(AbortSignal),
    )

    stream.emit((h) => h.onToken('Deux '))
    expect(result.current.text).toBe('Deux ')
    stream.emit((h) => h.onToken('jours'))
    expect(result.current.text).toBe('Deux jours')
    stream.emit((h) => h.onSources([aSource()]))
    expect(result.current.sources).toEqual([aSource()])
  })

  it("appelle onDone avec l'échange complet et repasse en idle", async () => {
    const stream = controlledStream()
    const onDone = vi.fn()
    const { result } = renderHook(() => useChatStream({ onDone }))

    act(() => void result.current.send('Télétravail ?'))
    stream.emit((h) => h.onToken('Deux jours'))
    stream.emit((h) => h.onSources([aSource()]))
    stream.emit((h) => h.onDone({ conversationId: 'conv-9', messageId: 'msg-1' }))
    await stream.finish()

    expect(result.current.status).toBe('idle')
    expect(result.current.conversationId).toBe('conv-9')
    expect(onDone).toHaveBeenCalledWith(
      { conversationId: 'conv-9', messageId: 'msg-1' },
      { question: 'Télétravail ?', answer: 'Deux jours', sources: [aSource()] },
    )
  })

  it("affiche le message de l'événement error", async () => {
    const stream = controlledStream()
    const { result } = renderHook(() => useChatStream())

    act(() => void result.current.send('Télétravail ?'))
    stream.emit((h) => h.onToken('Deux'))
    stream.emit((h) => h.onError('Le modèle ne répond pas'))
    await stream.finish()

    expect(result.current.status).toBe('error')
    expect(result.current.error).toBe('Le modèle ne répond pas')
    expect(result.current.text).toBe('Deux') // le début de réponse reste visible
  })

  it('affiche le détail du ProblemDetail quand la requête est refusée', async () => {
    const stream = controlledStream()
    const { result } = renderHook(() => useChatStream())

    act(() => void result.current.send('Q ?', 'autre'))
    await stream.fail(new ApiError(404, 'Conversation introuvable'))

    expect(result.current.status).toBe('error')
    expect(result.current.error).toBe('Conversation introuvable')
  })

  it('signale un flux coupé avant done', async () => {
    const stream = controlledStream()
    const { result } = renderHook(() => useChatStream())

    act(() => void result.current.send('Q ?'))
    stream.emit((h) => h.onToken('Deux'))
    await stream.finish()

    expect(result.current.status).toBe('error')
    expect(result.current.error).toBe('La réponse a été interrompue. Réessayez.')
  })

  it('cancel abandonne la requête et revient à idle', async () => {
    const stream = controlledStream()
    const { result } = renderHook(() => useChatStream())

    act(() => void result.current.send('Q ?'))
    act(() => result.current.cancel())

    expect(stream.signal()?.aborted).toBe(true)
    expect(result.current.status).toBe('idle')
    expect(result.current.question).toBe('')
  })

  it('abandonne la requête au démontage', async () => {
    const stream = controlledStream()
    const { result, unmount } = renderHook(() => useChatStream())

    act(() => void result.current.send('Q ?'))
    unmount()

    await waitFor(() => expect(stream.signal()?.aborted).toBe(true))
  })
})
