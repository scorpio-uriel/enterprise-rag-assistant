import { Navigate, Outlet } from 'react-router'
import type { Role } from '../../api/types'
import { useAuth } from './useAuth'

/**
 * Confort d'interface uniquement (AC9.1) : la vraie protection est le 403 renvoyé par le backend.
 */
export function RequireRole({ role }: { role: Role }) {
  const { user } = useAuth()
  return user?.role === role ? <Outlet /> : <Navigate to="/chat" replace />
}
