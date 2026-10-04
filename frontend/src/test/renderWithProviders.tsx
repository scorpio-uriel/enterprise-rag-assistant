import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { sessionStore } from '../api/client'
import type { Role } from '../api/types'
import { routes } from '../app/routes'

/** Simule une session ouverte, comme après un login réussi. */
export function loginAs(role: Role) {
  sessionStore.set({
    token: 'jwt-token',
    email: role === 'ADMIN' ? 'admin@acme.local' : 'user@acme.local',
    role,
    expiresAt: '2999-01-01T00:00:00Z',
  })
}

/** Monte l'application complète (vraies routes) à l'URL donnée, avec un cache neuf. */
export function renderApp(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [initialPath] })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...utils, router, queryClient }
}
