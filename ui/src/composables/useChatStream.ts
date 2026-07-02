import { reactive, ref } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import type { Source, ToolCall, ChatStreamData } from '@/types/chat'
import { api, getApiBase } from '@/utils/api'

export interface StreamMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: Source[]
  toolMetadata: ToolCall[]
  isStreaming: boolean
  error: string | null
  createdAt: string
  sessionId?: string
}

export function useChatStream() {
  const messages = reactive<StreamMessage[]>([])
  const input = ref('')
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const currentSessionId = ref<string | null>(null)

  let abortController: AbortController | null = null

  function findMessage(id: string) {
    return messages.find(m => m.id === id)
  }

  async function saveMessage(sessionId: string, msg: StreamMessage) {
    try {
      await api('/chat/save', {
        method: 'POST',
        body: { sessionId, role: msg.role, content: msg.content, sources: msg.sources, toolMetadata: msg.toolMetadata },
      })
    } catch { /* noop */ }
  }

  async function send(query: string, modelKey: string) {
    if (!query.trim() || isLoading.value) return

    isLoading.value = true
    error.value = null
    abortController = new AbortController()

    messages.push({
      id: crypto.randomUUID(),
      role: 'user',
      content: query,
      sources: [], toolMetadata: [],
      isStreaming: false, error: null,
      createdAt: new Date().toISOString(),
    })

    const assistantId = crypto.randomUUID()
    messages.push({
      id: assistantId,
      role: 'assistant',
      content: '',
      sources: [], toolMetadata: [],
      isStreaming: true, error: null,
      createdAt: new Date().toISOString(),
    })

    let textBuf = ''
    let sourcesBuf: Source[] = []
    input.value = ''

    try {
      await fetchEventSource(`${getApiBase()}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, modelKey, sessionId: currentSessionId.value }),
        signal: abortController.signal,
        openWhenClosed: false,

        onmessage(ev) {
          try {
            const data: ChatStreamData = JSON.parse(ev.data)
            const msg = findMessage(assistantId)
            if (!msg) return

            if (data.type === 'done') {
              msg.isStreaming = false
              if (data.sessionId) {
                currentSessionId.value = data.sessionId
                msg.sessionId = data.sessionId
              }
              const sid = currentSessionId.value
              if (sid) saveMessage(sid, msg)
              return
            }

            if (data.type === 'error') {
              msg.isStreaming = false
              msg.error = data.content ?? '未知错误'
              error.value = msg.error
              return
            }

            if (data.text) { textBuf += data.text; msg.content = textBuf }
            if (data.sources) { sourcesBuf = data.sources; msg.sources = sourcesBuf }
            if (data.sessionId) { currentSessionId.value = data.sessionId; msg.sessionId = data.sessionId }
          } catch {
            const msg = findMessage(assistantId)
            if (msg && ev.data && ev.data !== '[DONE]') {
              textBuf += ev.data
              msg.content = textBuf
            }
          }
        },

        onerror(err) {
          if (err.name !== 'AbortError') {
            error.value = err.message || '连接失败'
            const msg = findMessage(assistantId)
            if (msg) { msg.isStreaming = false; msg.error = err.message || '连接失败' }
            abortController?.abort()
          }
        },

        onclose() {
          const msg = findMessage(assistantId)
          if (msg) msg.isStreaming = false
        },
      })
    } catch (err) {
      if (err instanceof Error && err.name !== 'AbortError') error.value = err.message
      const msg = findMessage(assistantId)
      if (msg) msg.isStreaming = false
    } finally {
      isLoading.value = false
      abortController = null
    }
  }

  function stop() { abortController?.abort(); isLoading.value = false }
  function clearMessages() { messages.splice(0); currentSessionId.value = null }

  return { messages, input, isLoading, error, currentSessionId, send, stop, clearMessages }
}
