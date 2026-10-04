import { useState, type DragEvent } from 'react'

const ACCEPTED_EXTENSIONS = '.pdf,.md,.txt,.docx'

interface UploadDropzoneProps {
  onFiles: (files: File[]) => void
  disabled?: boolean
}

/** Zone de glisser-déposer, doublée d'un sélecteur de fichiers classique (accessibilité). */
export function UploadDropzone({ onFiles, disabled = false }: UploadDropzoneProps) {
  const [dragging, setDragging] = useState(false)

  function handleDragOver(event: DragEvent) {
    event.preventDefault() // sans cela, le navigateur ouvre le fichier au lieu de le déposer
    if (!disabled) setDragging(true)
  }

  function handleDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    if (disabled) return
    const files = Array.from(event.dataTransfer.files)
    if (files.length > 0) onFiles(files)
  }

  return (
    <div
      data-testid="dropzone"
      onDragOver={handleDragOver}
      onDragLeave={() => setDragging(false)}
      onDrop={handleDrop}
      className={`rounded-lg border-2 border-dashed p-8 text-center transition-colors ${
        dragging ? 'border-indigo-500 bg-indigo-50' : 'border-slate-300 bg-white'
      }`}
    >
      <p className="text-slate-600">
        Glissez vos fichiers ici (PDF, Markdown, texte, Word · 20 Mo max)
      </p>
      <p className="my-2 text-sm text-slate-400">ou</p>
      <label
        className={`inline-block cursor-pointer rounded bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700 ${
          disabled ? 'pointer-events-none opacity-50' : ''
        }`}
      >
        Choisir des fichiers
        <input
          type="file"
          multiple
          accept={ACCEPTED_EXTENSIONS}
          disabled={disabled}
          className="sr-only"
          onChange={(event) => {
            const files = Array.from(event.target.files ?? [])
            if (files.length > 0) onFiles(files)
            event.target.value = '' // permet de resélectionner le même fichier
          }}
        />
      </label>
    </div>
  )
}
