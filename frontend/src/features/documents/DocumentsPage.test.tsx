import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { DocumentDto } from '../../api/types'
import { aDocument, server } from '../../test/handlers'
import { loginAs, renderApp } from '../../test/renderWithProviders'
import { hasPendingWork } from './useDocuments'

function row(fileName: string) {
  return screen.getByText(fileName).closest('tr')!
}

describe('DocumentsPage', () => {
  beforeEach(() => loginAs('ADMIN'))

  it('affiche les documents avec leur statut', async () => {
    server.use(
      http.get('/api/documents', () =>
        HttpResponse.json([
          aDocument(),
          aDocument({
            id: 'doc-2',
            fileName: 'casse.pdf',
            status: 'FAILED',
            errorMessage: 'PDF illisible',
          }),
        ]),
      ),
    )
    renderApp('/documents')

    await screen.findByText('casse.pdf')
    expect(within(row('politique-teletravail.pdf')).getByText('INDEXED')).toBeInTheDocument()
    expect(within(row('casse.pdf')).getByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('PDF illisible')).toBeInTheDocument()
  })

  it('importe un fichier via le sélecteur puis rafraîchit la liste', async () => {
    let contentType: string | null = null
    let documents: DocumentDto[] = []
    server.use(
      http.get('/api/documents', () => HttpResponse.json(documents)),
      http.post('/api/documents', ({ request }) => {
        contentType = request.headers.get('Content-Type')
        documents = [aDocument({ id: 'doc-2', fileName: 'notes.md', status: 'INDEXED' })]
        return HttpResponse.json({ id: 'doc-2', status: 'PENDING' }, { status: 202 })
      }),
    )
    renderApp('/documents')
    await screen.findByText('Aucun document importé.')

    const file = new File(['# Notes'], 'notes.md', { type: 'text/markdown' })
    await userEvent.upload(screen.getByLabelText('Choisir des fichiers'), file)

    expect(await screen.findByText('notes.md')).toBeInTheDocument()
    // multipart avec boundary générée par le navigateur, pas un Content-Type forcé à la main
    expect(contentType).toMatch(/^multipart\/form-data; boundary=/)
  })

  it('importe un fichier déposé par glisser-déposer', async () => {
    const upload = vi.fn()
    server.use(
      http.post('/api/documents', () => {
        upload()
        return HttpResponse.json({ id: 'doc-2', status: 'PENDING' }, { status: 202 })
      }),
    )
    renderApp('/documents')
    await screen.findByText('politique-teletravail.pdf')

    const file = new File(['%PDF-'], 'guide.pdf', { type: 'application/pdf' })
    fireEvent.drop(screen.getByTestId('dropzone'), { dataTransfer: { files: [file] } })

    await waitFor(() => expect(upload).toHaveBeenCalledOnce())
  })

  it('affiche le détail du ProblemDetail quand le backend refuse un import', async () => {
    server.use(
      http.post('/api/documents', () =>
        HttpResponse.json(
          { title: 'Document en double', detail: 'Ce document a déjà été importé', status: 409 },
          { status: 409 },
        ),
      ),
    )
    renderApp('/documents')
    await screen.findByText('politique-teletravail.pdf')

    await userEvent.upload(
      screen.getByLabelText('Choisir des fichiers'),
      new File(['x'], 'doublon.txt', { type: 'text/plain' }),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'doublon.txt : Ce document a déjà été importé',
    )
  })

  // AC9.2 (version automatisée) : le statut évolue sans rechargement grâce au polling.
  it("rafraîchit le statut tant que l'indexation n'est pas terminée", async () => {
    const statuses: DocumentDto['status'][] = ['PENDING', 'INDEXING', 'INDEXED']
    let calls = 0
    server.use(
      http.get('/api/documents', () => {
        const status = statuses[Math.min(calls++, statuses.length - 1)]
        return HttpResponse.json([aDocument({ status })])
      }),
    )
    renderApp('/documents')

    expect(await screen.findByText('PENDING')).toBeInTheDocument()
    expect(await screen.findByText('INDEXING', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(await screen.findByText('INDEXED', {}, { timeout: 3000 })).toBeInTheDocument()

    // Une fois tous les documents dans un état final, le polling s'arrête.
    const callsWhenDone = calls
    await new Promise((resolve) => setTimeout(resolve, 2500))
    expect(calls).toBe(callsWhenDone)
  }, 10_000)

  it('supprime un document après confirmation', async () => {
    let documents = [aDocument()]
    const deleted = vi.fn()
    server.use(
      http.get('/api/documents', () => HttpResponse.json(documents)),
      http.delete('/api/documents/:id', ({ params }) => {
        deleted(params.id)
        documents = []
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderApp('/documents')

    await userEvent.click(
      await screen.findByRole('button', { name: 'Supprimer politique-teletravail.pdf' }),
    )

    expect(confirm).toHaveBeenCalledWith('Supprimer « politique-teletravail.pdf » ?')
    expect(await screen.findByText('Aucun document importé.')).toBeInTheDocument()
    expect(deleted).toHaveBeenCalledWith('doc-1')
  })

  it("n'appelle pas l'API si la suppression est annulée", async () => {
    const deleted = vi.fn()
    server.use(
      http.delete('/api/documents/:id', () => {
        deleted()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderApp('/documents')

    await userEvent.click(
      await screen.findByRole('button', { name: 'Supprimer politique-teletravail.pdf' }),
    )

    expect(deleted).not.toHaveBeenCalled()
    expect(screen.getByText('politique-teletravail.pdf')).toBeInTheDocument()
  })

  it("désactive la suppression d'un document en cours d'indexation", async () => {
    server.use(
      http.get('/api/documents', () => HttpResponse.json([aDocument({ status: 'INDEXING' })])),
    )
    renderApp('/documents')

    expect(
      await screen.findByRole('button', { name: 'Supprimer politique-teletravail.pdf' }),
    ).toBeDisabled()
  })
})

describe('hasPendingWork', () => {
  it('ne déclenche le polling que pour PENDING ou INDEXING', () => {
    expect(hasPendingWork(undefined)).toBe(false)
    expect(
      hasPendingWork([aDocument({ status: 'INDEXED' }), aDocument({ status: 'FAILED' })]),
    ).toBe(false)
    expect(hasPendingWork([aDocument({ status: 'PENDING' })])).toBe(true)
    expect(hasPendingWork([aDocument({ status: 'INDEXING' })])).toBe(true)
  })
})
