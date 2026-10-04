import type { ProblemDetail, Role } from './types'

/** Session stockée dans sessionStorage : survit au F5, disparaît à la fermeture de l'onglet. */
export interface StoredSession {
  token: string
  email: string
  role: Role
  expiresAt: string
}

const STORAGE_KEY = 'auth'
const LOGIN_PATH = '/api/auth/login'

export const sessionStore = {
  get(): StoredSession | null {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    try {
      return JSON.parse(raw) as StoredSession
    } catch {
      return null
    }
  },
  set(session: StoredSession) {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  },
  clear() {
    sessionStorage.removeItem(STORAGE_KEY)
  },
}

export class ApiError extends Error {
  readonly status: number
  readonly detail: string

  constructor(status: number, detail: string) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
  }
}

let onUnauthorized: () => void = () => {}

/** L'AuthProvider s'enregistre ici pour réagir à un 401 (token expiré ou invalide). */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

async function toApiError(response: Response): Promise<ApiError> {
  let detail = `Erreur ${response.status}`
  try {
    const problem = (await response.json()) as ProblemDetail
    detail = problem.detail ?? problem.title ?? detail
  } catch {
    // corps absent ou non JSON : on garde le message générique
  }
  return new ApiError(response.status, detail)
}

/**
 * Seul point d'accès HTTP du front : ajoute le Bearer, traduit les erreurs ProblemDetail
 * et déconnecte sur 401.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const session = sessionStore.get()
  if (session) headers.set('Authorization', `Bearer ${session.token}`)
  // Avec FormData, le navigateur doit générer lui-même le Content-Type et sa boundary.
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(path, { ...init, headers })

  if (!response.ok) {
    // Un 401 sur le login signifie « mauvais identifiants », pas « session expirée ».
    if (response.status === 401 && path !== LOGIN_PATH) {
      sessionStore.clear()
      onUnauthorized()
    }
    throw await toApiError(response)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
