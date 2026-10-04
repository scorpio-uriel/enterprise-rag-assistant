import { screen } from '@testing-library/react'
import { loginAs, renderApp } from '../test/renderWithProviders'

describe('protection des routes', () => {
  it('redirige un visiteur non connecté vers /login', async () => {
    const { router } = renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  // AC9.1
  it('ne montre pas le lien Documents à un USER', async () => {
    loginAs('USER')
    renderApp('/chat')

    expect(await screen.findByRole('heading', { name: 'Chat' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Documents' })).not.toBeInTheDocument()
  })

  // AC9.1
  it('redirige un USER qui tape /documents vers /chat', async () => {
    loginAs('USER')
    const { router } = renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Chat' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/chat')
  })

  it('donne accès à la page Documents à un ADMIN', async () => {
    loginAs('ADMIN')
    renderApp('/documents')

    expect(screen.getByRole('link', { name: 'Documents' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Documents' })).toBeInTheDocument()
    expect(await screen.findByText('politique-teletravail.pdf')).toBeInTheDocument()
  })

  it('considère une session expirée comme une déconnexion', async () => {
    sessionStorage.setItem(
      'auth',
      JSON.stringify({
        token: 't',
        email: 'a@acme.local',
        role: 'ADMIN',
        expiresAt: '2000-01-01T00:00:00Z',
      }),
    )
    const { router } = renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Connexion' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(sessionStorage.getItem('auth')).toBeNull()
  })
})
