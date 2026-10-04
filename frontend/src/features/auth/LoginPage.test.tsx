import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderApp } from '../../test/renderWithProviders'

async function submit(email: string, password: string) {
  await userEvent.type(screen.getByLabelText('Email'), email)
  await userEvent.type(screen.getByLabelText('Mot de passe'), password)
  await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
}

describe('LoginPage', () => {
  it('stocke la session et ouvre le chat après un login réussi', async () => {
    const { router } = renderApp('/login')

    await submit('admin@acme.local', 'secret')

    expect(await screen.findByRole('heading', { name: 'Chat' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/chat')
    expect(JSON.parse(sessionStorage.getItem('auth')!)).toMatchObject({
      token: 'jwt-token',
      email: 'admin@acme.local',
      role: 'ADMIN',
    })
    expect(screen.getByRole('link', { name: 'Documents' })).toBeInTheDocument()
  })

  it('affiche le message du backend sur des identifiants invalides', async () => {
    const { router } = renderApp('/login')

    await submit('admin@acme.local', 'mauvais')

    expect(await screen.findByRole('alert')).toHaveTextContent('Email ou mot de passe incorrect')
    expect(router.state.location.pathname).toBe('/login')
    expect(sessionStorage.getItem('auth')).toBeNull()
  })
})
