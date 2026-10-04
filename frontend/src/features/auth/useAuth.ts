import { createContext, useContext } from 'react'
import type { Role } from '../../api/types'

export interface AuthUser {
  email: string
  role: Role
}

export interface AuthContextValue {
  user: AuthUser | null
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth doit être utilisé sous <AuthProvider>')
  return value
}
