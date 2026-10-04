import { http, HttpResponse } from 'msw'
import { server } from '../test/handlers'
import { loginAs } from '../test/renderWithProviders'
import { ApiError, apiFetch, setUnauthorizedHandler } from './client'

describe('apiFetch', () => {
  afterEach(() => setUnauthorizedHandler(() => {}))

  it("ajoute l'en-tête Authorization quand une session existe", async () => {
    loginAs('ADMIN')
    let authorization: string | null = null
    server.use(
      http.get('/api/documents', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json([])
      }),
    )

    await apiFetch('/api/documents')

    expect(authorization).toBe('Bearer jwt-token')
  })

  it('laisse le navigateur fixer le Content-Type multipart (boundary) pour un FormData', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const body = new FormData()
    body.append('file', new File(['x'], 'a.txt'))

    await apiFetch('/api/documents', { method: 'POST', body })

    const headers = fetchSpy.mock.calls[0][1]!.headers as Headers
    expect(headers.has('Content-Type')).toBe(false)
  })

  it('traduit un ProblemDetail en ApiError', async () => {
    server.use(
      http.post('/api/documents', () =>
        HttpResponse.json(
          { title: 'Document en double', detail: 'Ce fichier existe déjà', status: 409 },
          { status: 409 },
        ),
      ),
    )

    const error = await apiFetch('/api/documents', { method: 'POST', body: new FormData() }).catch(
      (e: unknown) => e,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, detail: 'Ce fichier existe déjà' })
  })

  it('sur 401, efface la session et prévient le handler', async () => {
    loginAs('ADMIN')
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(http.get('/api/documents', () => new HttpResponse(null, { status: 401 })))

    await expect(apiFetch('/api/documents')).rejects.toBeInstanceOf(ApiError)

    expect(sessionStorage.getItem('auth')).toBeNull()
    expect(handler).toHaveBeenCalledOnce()
  })

  it('un 401 sur le login ne déclenche pas la déconnexion', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)

    await expect(
      apiFetch('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email: 'a@acme.local', password: 'mauvais' }),
      }),
    ).rejects.toMatchObject({ status: 401 })

    expect(handler).not.toHaveBeenCalled()
  })
})
