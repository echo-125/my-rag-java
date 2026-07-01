import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Message } from '@/api/types/session'
import type { ModelConfig, Source, ToolCall } from '@/api/types/chat'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: Date
  sessionId: string | null
  status: 'idle' | 'streaming' | 'completed' | 'error' | 'interrupted'
  sources: Source[]
  toolMetadata: ToolCall[]
  duration: number | null
  query?: string
  modelName?: string
}

export const useChatStore = defineStore('chat', () => {
  const currentSessionId = ref<string | null>(null)
  const messages = ref<Map<string, ChatMessage>>(new Map())
  const selectedMessageId = ref<string | null>(null)
  const models = ref<ModelConfig[]>([])
  const isStreaming = ref(false)

  function addMessage(msg: ChatMessage) {
    messages.value.set(msg.id, msg)
  }

  function updateMessage(id: string, updates: Partial<ChatMessage>) {
    const msg = messages.value.get(id)
    if (msg) {
      Object.assign(msg, updates)
    }
  }

  function selectMessage(id: string | null) {
    selectedMessageId.value = id
  }

  function resetChat() {
    currentSessionId.value = null
    messages.value.clear()
    selectedMessageId.value = null
  }

  return {
    currentSessionId,
    messages,
    selectedMessageId,
    models,
    isStreaming,
    addMessage,
    updateMessage,
    selectMessage,
    resetChat,
  }
})
