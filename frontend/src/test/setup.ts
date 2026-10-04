import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { server } from './handlers'

// onUnhandledRequest: 'error' : un appel réseau non prévu fait échouer le test.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  sessionStorage.clear()
  vi.restoreAllMocks()
})
afterAll(() => server.close())
