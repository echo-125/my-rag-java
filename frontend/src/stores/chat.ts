import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session, Message } from '@/api/types/session'
import type { ModelConfig, Source, ToolCall } from '@/api/types/chat'
import * as sessionApi from '@/api/session'
import * as chatApi from '@/api/chat'

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
  const selectedModelKey = ref('')
  const sessions = ref<Session[]>([])
  const isStreaming = ref(false)

  // 按时间排序的消息列表
  const messageList = computed(() => {
    return Array.from(messages.value.values())
      .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
  })

  // 当前会话的消息
  const currentMessages = computed(() => {
    return messageList.value.filter(m => m.sessionId === currentSessionId.value)
  })

  function addMessage(msg: ChatMessage) {
    messages.value.set(msg.id, msg)
  }

  function updateMessage(id: string, updates: Partial<ChatMessage>) {
    const msg = messages.value.get(id)
    if (msg) Object.assign(msg, updates)
  }

  function selectMessage(id: string | null) {
    selectedMessageId.value = id
  }

  function resetChat() {
    currentSessionId.value = null
    messages.value.clear()
    selectedMessageId.value = null
  }

  // ─── 会话管理 ───

  async function loadSessions() {
    sessions.value = await sessionApi.getSessions()
  }

  async function switchSession(sessionId: string) {
    currentSessionId.value = sessionId
    messages.value.clear()
    selectedMessageId.value = null

    try {
      const msgs = await sessionApi.getSessionMessages(sessionId)
      for (const msg of msgs) {
        const id = msg.id || crypto.randomUUID()
        addMessage({
          id,
          role: msg.role,
          content: msg.content,
          createdAt: new Date(msg.createdAt),
          sessionId,
          status: 'completed',
          sources: [],
          toolMetadata: [],
          duration: null,
        })
      }
    } catch {}
  }

  async function deleteSession(sessionId: string) {
    await sessionApi.deleteSession(sessionId)
    if (currentSessionId.value === sessionId) resetChat()
    await loadSessions()
  }

  // ─── 模型列表 ───

  async function loadModels() {
    models.value = await chatApi.getModels()
  }

  return {
    currentSessionId, messages, selectedMessageId, models, selectedModelKey, sessions, isStreaming,
    messageList, currentMessages,
    addMessage, updateMessage, selectMessage, resetChat,
    loadSessions, switchSession, deleteSession, loadModels,
  }
})
