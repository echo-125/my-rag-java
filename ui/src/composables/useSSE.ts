import { fetchEventSource } from '@microsoft/fetch-event-source'
import { getApiBase } from '@/utils/api'

export interface SSECallbacks<T = Record<string, unknown>> {
  onMessage: (data: T) => void
  onError?: (err: Error) => void
  onDone?: () => void
}

export function useSSE() {
  let abortController: AbortController | null = null
  let retryCount = 0
  const MAX_RETRIES = 3
  const BASE_DELAY = 1000

  async function connect<T = Record<string, unknown>>(
    path: string,
    body: Record<string, unknown>,
    callbacks: SSECallbacks<T>,
    options?: { maxRetries?: number },
  ) {
    const maxRetries = options?.maxRetries ?? MAX_RETRIES
    retryCount = 0
    abortController = new AbortController()

    async function attempt() {
      try {
        await fetchEventSource(`${getApiBase()}${path}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal: abortController!.signal,
          openWhenClosed: false,
          onmessage(ev) {
            if (ev.data === '[DONE]') {
              callbacks.onDone?.()
              return
            }
            try {
              const data = JSON.parse(ev.data) as T
              callbacks.onMessage(data)
            } catch {
              if (ev.data) {
                callbacks.onMessage(ev.data as T)
              }
            }
          },
          onerror(err) {
            if (err.name === 'AbortError') return 0
            if (retryCount < maxRetries) {
              const delay = BASE_DELAY * Math.pow(2, retryCount)
              retryCount++
              setTimeout(() => attempt(), delay)
              return 0 // 阻止库内置重试，由我们的逻辑控制
            } else {
              callbacks.onError?.(err)
              abortController?.abort()
            }
            return 0
          },
          onclose() {
            callbacks.onDone?.()
          },
        })
      } catch (err) {
        if (err instanceof Error && err.name !== 'AbortError') {
          callbacks.onError?.(err)
        }
      }
    }

    await attempt()
  }

  function disconnect() {
    abortController?.abort()
    retryCount = 0
  }

  return { connect, disconnect }
}
