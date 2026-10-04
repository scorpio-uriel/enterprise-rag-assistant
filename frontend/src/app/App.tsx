import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { ApiError } from '../api/client'
import { routes } from './routes'

// Créés une seule fois, hors du rendu : le cache survit aux re-rendus.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Une erreur 4xx (401, 403, 404…) ne se corrige pas en réessayant.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 1,
    },
  },
})
const router = createBrowserRouter(routes)

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
