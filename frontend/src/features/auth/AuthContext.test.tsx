import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/handlers'
import { loginAs, renderApp } from '../../test/renderWithProviders'

describe('AuthContext', () => {
  // AC9.4
  it('la déconnexion vide sessionStorage et renvoie vers /login', async () => {
    loginAs('ADMIN')
    const { router } = renderApp('/chat')

    await userEvent.click(await screen.findByRole('button', { name: 'Se déconnecter' }))

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(sessionStorage.length).toBe(0)
  })

  it('un 401 (token expiré) renvoie vers /login', async () => {
    server.use(
      http.get('/api/documents', () =>
        HttpResponse.json({ title: 'Unauthorized', status: 401 }, { status: 401 }),
      ),
    )
    loginAs('ADMIN')
    const { router } = renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(sessionStorage.getItem('auth')).toBeNull()
  })
})
