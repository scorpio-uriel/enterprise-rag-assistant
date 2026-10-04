import { Navigate, Outlet, type RouteObject } from 'react-router'
import { AuthProvider } from '../features/auth/AuthContext'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAuth } from '../features/auth/RequireAuth'
import { RequireRole } from '../features/auth/RequireRole'
import { ChatPage } from '../features/chat/ChatPage'
import { DocumentsPage } from '../features/documents/DocumentsPage'
import { Layout } from './Layout'

/**
 * Arbre des routes, partagé par l'application (createBrowserRouter) et les tests
 * (createMemoryRouter). L'AuthProvider est à la racine car il a besoin de useNavigate.
 */
export const routes: RouteObject[] = [
  {
    element: (
      <AuthProvider>
        <Outlet />
      </AuthProvider>
    ),
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <Layout />,
            children: [
              { path: '/chat/:id?', element: <ChatPage /> },
              {
                element: <RequireRole role="ADMIN" />,
                children: [{ path: '/documents', element: <DocumentsPage /> }],
              },
              { path: '*', element: <Navigate to="/chat" replace /> },
            ],
          },
        ],
      },
    ],
  },
]
