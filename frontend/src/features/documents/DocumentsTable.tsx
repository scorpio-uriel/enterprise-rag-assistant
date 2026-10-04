import type { DocumentDto } from '../../api/types'
import { Button } from '../../components/Button'
import { formatSize } from './formatSize'
import { StatusBadge } from './StatusBadge'

interface DocumentsTableProps {
  documents: DocumentDto[]
  onDelete: (document: DocumentDto) => void
  deletingId?: string
}

export function DocumentsTable({ documents, onDelete, deletingId }: DocumentsTableProps) {
  if (documents.length === 0) {
    return <p className="text-slate-500">Aucun document importé.</p>
  }

  return (
    <table className="w-full overflow-hidden rounded-lg bg-white text-left text-sm shadow">
      <thead className="bg-slate-100 text-slate-600">
        <tr>
          <th className="px-4 py-2">Fichier</th>
          <th className="px-4 py-2">Taille</th>
          <th className="px-4 py-2">Statut</th>
          <th className="px-4 py-2">Chunks</th>
          <th className="px-4 py-2">Importé le</th>
          <th className="px-4 py-2">
            <span className="sr-only">Actions</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {documents.map((doc) => (
          <tr key={doc.id} className="border-t border-slate-100">
            <td className="px-4 py-2 font-medium text-slate-800">{doc.fileName}</td>
            <td className="px-4 py-2 text-slate-600">{formatSize(doc.sizeBytes)}</td>
            <td className="px-4 py-2">
              <StatusBadge status={doc.status} />
              {doc.status === 'FAILED' && doc.errorMessage && (
                <p className="mt-1 text-xs text-red-600">{doc.errorMessage}</p>
              )}
            </td>
            <td className="px-4 py-2 text-slate-600">{doc.chunkCount}</td>
            <td className="px-4 py-2 text-slate-600">
              {new Date(doc.createdAt).toLocaleString('fr-FR')}
            </td>
            <td className="px-4 py-2 text-right">
              <Button
                variant="danger"
                aria-label={`Supprimer ${doc.fileName}`}
                // Le backend refuse (409) de supprimer un document en cours d'indexation.
                disabled={doc.status === 'INDEXING' || deletingId === doc.id}
                onClick={() => onDelete(doc)}
              >
                Supprimer
              </Button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
