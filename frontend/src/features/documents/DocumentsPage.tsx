import { useState } from 'react'
import type { DocumentDto } from '../../api/types'
import { ErrorBanner } from '../../components/ErrorBanner'
import { Spinner } from '../../components/Spinner'
import { DocumentsTable } from './DocumentsTable'
import { UploadDropzone } from './UploadDropzone'
import { useDeleteDocument, useDocumentsQuery, useUploadDocument } from './useDocuments'

export function DocumentsPage() {
  const documents = useDocumentsQuery()
  const upload = useUploadDocument()
  const remove = useDeleteDocument()
  const [errors, setErrors] = useState<string[]>([])

  async function handleFiles(files: File[]) {
    setErrors([])
    // Un import à la fois : chaque fichier a son propre message d'erreur (400, 409…).
    for (const file of files) {
      try {
        await upload.mutateAsync(file)
      } catch (error) {
        setErrors((prev) => [...prev, `${file.name} : ${(error as Error).message}`])
      }
    }
  }

  function handleDelete(document: DocumentDto) {
    if (!window.confirm(`Supprimer « ${document.fileName} » ?`)) return
    setErrors([])
    remove.mutate(document.id, {
      onError: (error) => setErrors([`${document.fileName} : ${error.message}`]),
    })
  }

  return (
    <section className="space-y-6">
      <h1 className="text-xl font-semibold text-slate-800">Documents</h1>
      <UploadDropzone onFiles={handleFiles} disabled={upload.isPending} />
      {upload.isPending && <Spinner label="Import en cours…" />}
      {errors.map((message) => (
        <ErrorBanner key={message} message={message} />
      ))}
      {documents.isPending && <Spinner />}
      {documents.error && <ErrorBanner message={documents.error.message} />}
      {documents.data && (
        <DocumentsTable
          documents={documents.data}
          onDelete={handleDelete}
          deletingId={remove.isPending ? remove.variables : undefined}
        />
      )}
    </section>
  )
}
