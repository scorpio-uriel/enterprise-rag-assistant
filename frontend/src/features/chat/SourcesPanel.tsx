import type { SourceDto } from '../../api/types'
import { formatScore } from './formatScore'

/**
 * Extraits cités sous une réponse (AC6.3). <details> est dépliable nativement : pas d'état React,
 * et le clavier comme les lecteurs d'écran le gèrent sans code supplémentaire.
 */
export function SourcesPanel({ sources }: { sources: SourceDto[] }) {
  return (
    <details className="mt-2 rounded border border-slate-200 bg-slate-50 text-sm">
      <summary className="cursor-pointer px-3 py-1.5 font-medium text-slate-600 select-none">
        Sources ({sources.length})
      </summary>
      <ol className="space-y-2 border-t border-slate-200 px-3 py-2">
        {sources.map((source, index) => (
          <li key={index}>
            <div className="flex items-baseline gap-2">
              <span className="font-medium text-slate-800">{source.fileName}</span>
              {source.page !== null && <span className="text-slate-500">p. {source.page}</span>}
              <span className="ml-auto text-xs text-slate-500">{formatScore(source.score)}</span>
            </div>
            <p className="mt-0.5 text-slate-600 italic">{source.excerpt}</p>
          </li>
        ))}
      </ol>
    </details>
  )
}
