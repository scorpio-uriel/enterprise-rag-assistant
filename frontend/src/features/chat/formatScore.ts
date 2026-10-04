/** Score de similarité (0 à 1) en pourcentage arrondi, avec l'espace insécable française. */
export function formatScore(score: number): string {
  return `${Math.round(score * 100)}\u00a0%`
}
