import { defineStore } from 'pinia'
import type { ChatSession } from '@/types/chat'
import { api } from '@/utils/api'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const activeSessionId = ref<string | null>(null)
  const loadingSessions = ref(false)

  async function fetchSessions() {
    loadingSessions.value = true
    try {
      sessions.value = await api<ChatSession[]>('/sessions')
    } catch {
      sessions.value = []
    } finally {
      loadingSessions.value = false
    }
  }

  async function deleteSession(id: string) {
    await api(`/sessions/${id}`, { method: 'DELETE' })
    sessions.value = sessions.value.filter(s => s.id !== id)
    if (activeSessionId.value === id) {
      activeSessionId.value = null
    }
  }

  function setActiveSession(id: string | null) {
    activeSessionId.value = id
  }

  return {
    sessions,
    activeSessionId,
    loadingSessions,
    fetchSessions,
    deleteSession,
    setActiveSession,
  }
})
