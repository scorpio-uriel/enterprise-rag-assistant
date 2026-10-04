import { Navigate, Outlet } from 'react-router'
import { useAuth } from './useAuth'

export function RequireAuth() {
  const { user } = useAuth()
  return user ? <Outlet /> : <Navigate to="/login" replace />
}
