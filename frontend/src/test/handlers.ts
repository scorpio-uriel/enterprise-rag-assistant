import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { DocumentDto } from '../api/types'

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
]

export const server = setupServer(...handlers)
