import { render, screen } from '@testing-library/react'
import type { DocumentStatus } from '../../api/types'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it.each<DocumentStatus>(['PENDING', 'INDEXING', 'INDEXED', 'FAILED'])('affiche %s', (status) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(status)).toBeInTheDocument()
  })
})
