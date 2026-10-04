import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { login as loginRequest } from '../../api/auth'
import { sessionStore, setUnauthorizedHandler, type StoredSession } from '../../api/client'
import { AuthContext, type AuthContextValue, type AuthUser } from './useAuth'

/** Session encore valide au chargement de la page, sinon null (token expiré = déconnecté). */
function readSession(): StoredSession | null {
  const session = sessionStore.get()
  if (session && new Date(session.expiresAt).getTime() <= Date.now()) {
    sessionStore.clear()
    return null
  }
  return session
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [user, setUser] = useState<AuthUser | null>(() => {
    const session = readSession()
    return session ? { email: session.email, role: session.role } : null
  })

  const logout = useCallback(() => {
    sessionStore.clear()
    queryClient.clear() // aucune donnée de l'utilisateur précédent ne reste en cache
    setUser(null)
    navigate('/login', { replace: true })
  }, [navigate, queryClient])

  // Un 401 reçu par n'importe quel appel API (token expiré) vaut déconnexion.
  useEffect(() => {
    setUnauthorizedHandler(logout)
    return () => setUnauthorizedHandler(() => {})
  }, [logout])

  const login = useCallback(async (email: string, password: string) => {
    const response = await loginRequest(email, password)
    sessionStore.set({
      token: response.token,
      email,
      role: response.role,
      expiresAt: response.expiresAt,
    })
    setUser({ email, role: response.role })
  }, [])

  const value = useMemo<AuthContextValue>(() => ({ user, login, logout }), [user, login, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
