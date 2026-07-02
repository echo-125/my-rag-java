<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useAppStore } from '@/stores/app'
import { useSettingsStore } from '@/stores/settings'
import { useChatStream, type StreamMessage } from '@/composables/useChatStream'
import { useThrottleFn } from '@vueuse/core'
import { toast } from 'vue-sonner'
import SessionPanel from './components/SessionPanel.vue'
import MessageUser from './components/MessageUser.vue'
import MessageAssistant from './components/MessageAssistant.vue'
import InputArea from './components/InputArea.vue'
import ChatStatusBar from './components/ChatStatusBar.vue'
import WelcomePanel from './components/WelcomePanel.vue'
import DiagPanel from './components/DiagPanel.vue'
import { api } from '@/utils/api'

const chatStore = useChatStore()
const appStore = useAppStore()
const settingsStore = useSettingsStore()
const { messages, input, isLoading, error: chatError, currentSessionId, send, stop, clearMessages } = useChatStream()

const messageListRef = ref<HTMLElement | null>(null)
const selectedModel = ref('')
const selectedDiagMessage = ref<StreamMessage | null>(null)

const showWelcome = computed(() => messages.length === 0)

const throttledScroll = useThrottleFn(() => {
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}, 100)

watch(
  () => messages.length,
  () => nextTick(throttledScroll),
)

watch(
  () => messages.at(-1)?.content,
  () => nextTick(throttledScroll),
)

async function handleSend(query: string) {
  await send(query, selectedModel.value)
}

function handleQuickAction(query: string) {
  handleSend(query)
}

function handleMessageClick(msg: StreamMessage) {
  selectedDiagMessage.value = msg
  if (!appStore.diagPanelOpen) appStore.toggleDiag()
}

async function handleFeedback(messageId: string, positive: boolean) {
  try {
    await api('/feedback', {
      method: 'POST',
      body: { messageId, positive },
    })
    toast.success(positive ? '感谢反馈' : '已收到，我们会改进')
  } catch {
    toast.error('反馈提交失败')
  }
}

function handleNewChat() {
  clearMessages()
  chatStore.setActiveSession(null)
}

onMounted(async () => {
  chatStore.fetchSessions()
  await settingsStore.fetchLlmConfigs()
  const active = settingsStore.llmConfigs.find(c => c.isActive)
  if (active) selectedModel.value = active.id
})
</script>

<template>
  <div class="flex flex-1 overflow-hidden">
    <aside class="w-56 shrink-0 border-r border-default bg-default overflow-hidden hidden md:flex">
      <SessionPanel @new-chat="handleNewChat" />
    </aside>

    <div class="flex flex-1 flex-col overflow-hidden">
      <ChatStatusBar
        :model-name="selectedModel"
        :session-id="chatStore.activeSessionId ?? undefined"
      />

      <div ref="messageListRef" class="flex-1 overflow-y-auto">
        <WelcomePanel v-if="showWelcome" @quick-action="handleQuickAction" />
        <div v-else class="py-4 space-y-1">
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="cursor-pointer hover:bg-elevated/30 transition-colors rounded-lg"
            @click="handleMessageClick(msg)"
          >
            <MessageUser v-if="msg.role === 'user'" :message="msg" />
            <MessageAssistant
              v-else
              :message="msg"
              @feedback="handleFeedback"
            />
          </div>
        </div>
      </div>

      <InputArea
        :loading="isLoading"
        @send="handleSend"
        @stop="stop"
      />
    </div>

    <DiagPanel :message="selectedDiagMessage" />
  </div>
</template>
