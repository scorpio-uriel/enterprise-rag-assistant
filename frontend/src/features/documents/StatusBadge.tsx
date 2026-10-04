import type { DocumentStatus } from '../../api/types'

const STYLES: Record<DocumentStatus, string> = {
  PENDING: 'bg-slate-100 text-slate-700',
  INDEXING: 'bg-blue-100 text-blue-700 animate-pulse',
  INDEXED: 'bg-green-100 text-green-700',
  FAILED: 'bg-red-100 text-red-700',
}

export function StatusBadge({ status }: { status: DocumentStatus }) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-xs font-semibold ${STYLES[status]}`}
    >
      {status}
    </span>
  )
}
