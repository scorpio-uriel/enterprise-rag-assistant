import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { aSource } from '../../test/handlers'
import { formatScore } from './formatScore'
import { SourcesPanel } from './SourcesPanel'

describe('SourcesPanel (AC6.3)', () => {
  const sources = [
    aSource(),
    aSource({ fileName: 'charte.md', page: null, excerpt: 'Charte informatique.', score: 0.712 }),
  ]

  it('est replié par défaut puis se déplie au clic', async () => {
    render(<SourcesPanel sources={sources} />)

    const excerpt = screen.getByText('Charte informatique.')
    expect(excerpt).not.toBeVisible()

    await userEvent.click(screen.getByText('Sources (2)'))

    expect(excerpt).toBeVisible()
  })

  it('affiche fichier, page si connue, extrait et score en pourcentage', () => {
    render(<SourcesPanel sources={sources} />)

    expect(screen.getByText('politique-teletravail.pdf')).toBeInTheDocument()
    expect(screen.getByText('p. 3')).toBeInTheDocument()
    expect(screen.getByText('87 %')).toBeInTheDocument()
    expect(screen.getByText('charte.md')).toBeInTheDocument()
    expect(screen.getAllByText(/^p\. /)).toHaveLength(1) // pas de page pour le .md
    expect(screen.getByText('71 %')).toBeInTheDocument()
  })
})

describe('formatScore', () => {
  it('arrondit au pourcentage entier', () => {
    expect(formatScore(0.874)).toBe('87 %')
    expect(formatScore(1)).toBe('100 %')
  })
})
