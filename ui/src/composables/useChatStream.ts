import { fetchEventSource } from '@microsoft/fetch-event-source'
import type { ChatStreamEvent, Source, ToolCall } from '@/types/chat'
import { api, getApiBase } from '@/utils/api'

export interface StreamMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources: Source[]
  toolMetadata: ToolCall[]
  isStreaming: boolean
  createdAt: string
  sessionId?: string
}

export function useChatStream() {
  const messages = ref<StreamMessage[]>([])
  const input = ref('')
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const currentSessionId = ref<string | null>(null)

  let abortController: AbortController | null = null

  function addUserMessage(content: string) {
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'user',
      content,
      sources: [],
      toolMetadata: [],
      isStreaming: false,
      createdAt: new Date().toISOString(),
    })
  }

  function createAssistantMessage(): StreamMessage {
    const msg: StreamMessage = {
      id: crypto.randomUUID(),
      role: 'assistant',
      content: '',
      sources: [],
      toolMetadata: [],
      isStreaming: true,
      createdAt: new Date().toISOString(),
    }
    messages.value.push(msg)
    return msg
  }

  async function saveMessage(sessionId: string, msg: StreamMessage) {
    try {
      await api('/chat/save', {
        method: 'POST',
        body: {
          sessionId,
          role: msg.role,
          content: msg.content,
          sources: msg.sources,
          toolMetadata: msg.toolMetadata,
        },
      })
    } catch {
      // 保存失败不影响用户体验
    }
  }

  async function send(query: string, modelKey: string) {
    if (!query.trim() || isLoading.value) return

    isLoading.value = true
    error.value = null
    abortController = new AbortController()

    addUserMessage(query)
    const assistantMsg = createAssistantMessage()
    input.value = ''

    try {
      await fetchEventSource(`${getApiBase()}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query,
          modelKey,
          sessionId: currentSessionId.value,
        }),
        signal: abortController.signal,
        onmessage(ev) {
          try {
            const event: ChatStreamEvent = JSON.parse(ev.data)
            switch (event.type) {
              case 'text':
                if (event.content) assistantMsg.content += event.content
                break
              case 'sources':
                if (event.sources) assistantMsg.sources = event.sources
                break
              case 'toolMetadata':
                if (event.toolMetadata) assistantMsg.toolMetadata = event.toolMetadata
                break
              case 'done': {
                assistantMsg.isStreaming = false
                // 从 done 事件中提取 sessionId 并保存消息
                const doneData = event as Record<string, unknown>
                if (typeof doneData.sessionId === 'string') {
                  currentSessionId.value = doneData.sessionId
                  assistantMsg.sessionId = doneData.sessionId
                }
                const sid = currentSessionId.value
                if (sid) {
                  saveMessage(sid, assistantMsg)
                }
                break
              }
              case 'error':
                error.value = event.content ?? '未知错误'
                assistantMsg.isStreaming = false
                break
            }
          } catch {
            // 非 JSON 行，追加为纯文本
            if (ev.data && ev.data !== '[DONE]') {
              assistantMsg.content += ev.data
            }
          }
        },
        onerror(err) {
          if (err.name !== 'AbortError') {
            error.value = err.message || '连接失败'
            assistantMsg.isStreaming = false
          }
          return 0
        },
        onclose() {
          assistantMsg.isStreaming = false
        },
      })
    } catch (err) {
      if (err instanceof Error && err.name !== 'AbortError') {
        error.value = err.message
      }
      assistantMsg.isStreaming = false
    } finally {
      isLoading.value = false
      abortController = null
    }
  }

  function stop() {
    abortController?.abort()
    isLoading.value = false
  }

  function clearMessages() {
    messages.value = []
    currentSessionId.value = null
  }

  return {
    messages,
    input,
    isLoading,
    error,
    currentSessionId,
    send,
    stop,
    clearMessages,
  }
}
