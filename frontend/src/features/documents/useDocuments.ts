import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteDocument, listDocuments, uploadDocument } from '../../api/documents'
import type { DocumentDto } from '../../api/types'

export const DOCUMENTS_KEY = ['documents']
export const POLLING_INTERVAL_MS = 2000

/** Vrai tant qu'au moins un document n'a pas atteint un statut final. */
export function hasPendingWork(documents: DocumentDto[] | undefined): boolean {
  return documents?.some((d) => d.status === 'PENDING' || d.status === 'INDEXING') ?? false
}

/** Liste des documents, rafraîchie toutes les 2 s tant qu'une indexation est en cours (AC9.2). */
export function useDocumentsQuery() {
  return useQuery({
    queryKey: DOCUMENTS_KEY,
    queryFn: listDocuments,
    refetchInterval: (query) => (hasPendingWork(query.state.data) ? POLLING_INTERVAL_MS : false),
  })
}

export function useUploadDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: uploadDocument,
    // Le nouveau document arrive en PENDING : le refetch réactive le polling.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DOCUMENTS_KEY }),
  })
}

export function useDeleteDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteDocument,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DOCUMENTS_KEY }),
  })
}
