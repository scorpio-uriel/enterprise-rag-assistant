# Frontend — Enterprise RAG Assistant

React + TypeScript (Vite), Tailwind CSS v4, React Router, TanStack Query. Tests : Vitest, React Testing Library, MSW.

```bash
npm install
npm run dev          # http://localhost:5173, /api relayé vers http://localhost:8080
npm test             # Vitest (une passe)
npm run lint         # oxlint
npm run typecheck    # tsc
npm run format       # Prettier
npm run build        # dist/
```

Organisation : `src/api` (seule couche qui connaît les URL), `src/app` (routes, layout), `src/features/*` (découpage par fonctionnalité), `src/test` (setup Vitest et fausse API MSW).
