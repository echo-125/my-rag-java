import { ref } from 'vue'
import type { Source, ToolCall } from '@/api/types/chat'

export interface UseStreamChatOptions {
  onToken?: (text: string) => void
  onSources?: (sources: Source[]) => void
  onToolMetadata?: (tools: ToolCall[]) => void
  onComplete?: (fullText: string) => void
  onError?: (error: string) => void
}

export function useStreamChat(options?: UseStreamChatOptions) {
  const status = ref<'idle' | 'streaming' | 'completed' | 'error' | 'interrupted'>('idle')
  const fullText = ref('')
  const sources = ref<Source[]>([])
  const toolMetadata = ref<ToolCall[]>([])
  const errorMessage = ref('')
  const startTime = ref(0)
  const endTime = ref(0)

  let controller: AbortController | null = null
  let renderScheduled = false
  let pendingText = ''

  // rAF throttle — 每帧最多渲染一次
  function scheduleRender() {
    if (renderScheduled) return
    renderScheduled = true
    requestAnimationFrame(() => {
      fullText.value = pendingText
      options?.onToken?.(pendingText)
      renderScheduled = false
    })
  }

  async function send(query: string, modelKey: string, sessionId: string | null) {
    status.value = 'streaming'
    fullText.value = ''
    pendingText = ''
    sources.value = []
    toolMetadata.value = []
    errorMessage.value = ''
    startTime.value = Date.now()
    controller = new AbortController()

    try {
      const resp = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, modelKey, sessionId }),
        signal: controller.signal,
      })

      if (!resp.ok) throw new Error('请求失败 HTTP ' + resp.status)

      const reader = resp.body!.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value, { stream: true })
        buffer += chunk
        const lines = buffer.split('\n')
        buffer = lines.pop()! // 保留不完整的最后一行

        for (const line of lines) {
          const t = line.trim()
          if (t.startsWith('data:') && t.length > 6) {
            try {
              const payload = JSON.parse(t.substring(5))
              if (payload.text) pendingText += payload.text
              if (payload.sources && payload.sources.length > 0) {
                sources.value = payload.sources
                options?.onSources?.(payload.sources)
              }
              if (payload.toolMetadata && payload.toolMetadata.length > 0) {
                toolMetadata.value.push(...payload.toolMetadata)
                options?.onToolMetadata?.(payload.toolMetadata)
              }
              scheduleRender()
            } catch {
              // 非 JSON 行：可能是纯文本 token
              pendingText += t.substring(5).replace(/^"|"$/g, '').replace(/\\n/g, '\n')
              scheduleRender()
            }
          }
        }
      }

      fullText.value = pendingText
      status.value = 'completed'
      endTime.value = Date.now()
      options?.onComplete?.(fullText.value)
    } catch (e: any) {
      if (e.name === 'AbortError') {
        // 用户主动中断
        status.value = 'completed'
        fullText.value = pendingText
        endTime.value = Date.now()
      } else if (status.value === 'streaming') {
        // 流中断（网络错误等）
        status.value = 'interrupted'
        fullText.value = pendingText
        errorMessage.value = '连接中断，已收到的内容已保留'
        endTime.value = Date.now()
        options?.onError?.(errorMessage.value)
      } else {
        status.value = 'error'
        errorMessage.value = e.message || '未知错误'
        endTime.value = Date.now()
        options?.onError?.(errorMessage.value)
      }
    } finally {
      controller = null
    }
  }

  function abort() {
    controller?.abort()
  }

  function retry(query: string, modelKey: string, sessionId: string | null) {
    // 重试不重放（后端无幂等保护），而是追加新消息
    send(query, modelKey, sessionId)
  }

  return { status, fullText, sources, toolMetadata, errorMessage, startTime, endTime, send, abort, retry }
}
