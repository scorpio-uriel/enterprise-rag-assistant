import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button } from '../../components/Button'

export const MAX_QUESTION_LENGTH = 1000 // même limite que @Size sur ChatRequest

interface ChatInputProps {
  onSend: (question: string) => void
  disabled: boolean
}

/** Zone de saisie : Entrée envoie, Maj+Entrée va à la ligne. Désactivée pendant le streaming. */
export function ChatInput({ onSend, disabled }: ChatInputProps) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Rend le focus à la fin d'une réponse, pour enchaîner sur la question suivante.
  useEffect(() => {
    if (!disabled) textareaRef.current?.focus()
  }, [disabled])

  function submit() {
    const question = value.trim()
    if (!question || disabled) return
    onSend(question)
    setValue('')
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // isComposing : Entrée valide aussi une saisie IME (accents composés, japonais…).
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <form
      className="flex items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        submit()
      }}
    >
      <label htmlFor="chat-question" className="sr-only">
        Votre question
      </label>
      <textarea
        id="chat-question"
        ref={textareaRef}
        rows={2}
        maxLength={MAX_QUESTION_LENGTH}
        value={value}
        disabled={disabled}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Posez votre question… (Entrée pour envoyer, Maj+Entrée pour aller à la ligne)"
        className="flex-1 resize-none rounded border border-slate-300 px-3 py-2 text-sm focus:border-indigo-500 focus:outline-none disabled:bg-slate-100"
      />
      <Button type="submit" disabled={disabled || value.trim() === ''}>
        Envoyer
      </Button>
    </form>
  )
}
