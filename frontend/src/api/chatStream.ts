import { fetchEventSource } from '@microsoft/fetch-event-source'
import { authHeaders, handleUnauthorized, toApiError } from './client'
import type { ChatRequest, DoneEvent, SourceDto } from './types'

export interface ChatStreamHandlers {
  onToken: (text: string) => void
  onSources: (sources: SourceDto[]) => void
  onDone: (done: DoneEvent) => void
  onError: (message: string) => void
}

/**
 * POST /api/chat lu en SSE. EventSource (l'API native) ne permet ni POST ni en-tête
 * Authorization : fetch-event-source s'appuie sur fetch et découpe le flux en événements.
 *
 * La promesse est résolue à la fin du flux ou sur abort, et rejetée si la requête échoue
 * (statut HTTP d'erreur, coupure réseau). Elle n'est jamais rejouée : renvoyer la question
 * créerait un second échange côté backend.
 */
export function streamChat(
  request: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  return fetchEventSource('/api/chat', {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(request),
    signal,
    // Par défaut, la bibliothèque coupe le flux quand l'onglet est masqué et le rouvre au retour,
    // ce qui reposterait la question.
    openWhenHidden: true,
    // Résolu à l'appel (et non capturé au chargement du module) : MSW peut l'intercepter en test.
    fetch: (input, init) => fetch(input, init),

    async onopen(response) {
      if (!response.ok) {
        if (response.status === 401) handleUnauthorized()
        throw await toApiError(response) // 400 (validation), 404 (conversation d'autrui)…
      }
      if (!response.headers.get('Content-Type')?.startsWith('text/event-stream')) {
        throw new Error('Réponse inattendue du serveur')
      }
    },

    onmessage(event) {
      switch (event.event) {
        case 'token':
          handlers.onToken((JSON.parse(event.data) as { text: string }).text)
          break
        case 'sources':
          handlers.onSources(JSON.parse(event.data) as SourceDto[])
          break
        case 'done':
          handlers.onDone(JSON.parse(event.data) as DoneEvent)
          break
        case 'error':
          handlers.onError((JSON.parse(event.data) as { message: string }).message)
          break
      }
    },

    // Relancer l'erreur arrête les tentatives : sans cela, la bibliothèque réessaie en boucle.
    onerror(error) {
      throw error
    },
  })
}
