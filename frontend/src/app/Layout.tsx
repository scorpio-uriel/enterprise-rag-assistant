import { NavLink, Outlet } from 'react-router'
import { Button } from '../components/Button'
import { useAuth } from '../features/auth/useAuth'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `rounded px-3 py-2 text-sm font-medium ${isActive ? 'bg-slate-700 text-white' : 'text-slate-300 hover:text-white'}`

export function Layout() {
  const { user, logout } = useAuth()

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-slate-800">
        <nav className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-3">
          <span className="mr-4 font-semibold text-white">RAG Assistant</span>
          <NavLink to="/chat" className={linkClass}>
            Chat
          </NavLink>
          {user?.role === 'ADMIN' && (
            <NavLink to="/documents" className={linkClass}>
              Documents
            </NavLink>
          )}
          <span className="ml-auto text-sm text-slate-300">{user?.email}</span>
          <Button variant="secondary" onClick={logout}>
            Se déconnecter
          </Button>
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
