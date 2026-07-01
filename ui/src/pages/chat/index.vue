<script setup lang="ts">
import { useChatStore } from '@/stores/chat'
import { useAppStore } from '@/stores/app'
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
const chatStream = useChatStream()

const messageListRef = ref<HTMLElement | null>(null)
const selectedModel = ref('default')
const selectedDiagMessage = ref<StreamMessage | null>(null)

const showWelcome = computed(() => chatStream.messages.value.length === 0)

const throttledScroll = useThrottleFn(() => {
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}, 100)

// 自动滚动到底部
watch(
  () => chatStream.messages.value.length,
  () => nextTick(throttledScroll),
)

// 流式内容更新时滚动
watch(
  () => chatStream.messages.value.at(-1)?.content,
  () => nextTick(throttledScroll),
)

async function handleSend(query: string) {
  await chatStream.send(query, selectedModel.value)
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
    toast.success(positive ? '感谢反馈 👍' : '已收到，我们会改进')
  } catch {
    toast.error('反馈提交失败')
  }
}

function handleNewChat() {
  chatStream.clearMessages()
  chatStore.setActiveSession(null)
}

onMounted(() => {
  chatStore.fetchSessions()
})
</script>

<template>
  <div class="flex flex-1 overflow-hidden">
    <!-- 左侧会话栏 -->
    <aside class="w-56 shrink-0 border-r border-default bg-default overflow-hidden hidden md:flex">
      <SessionPanel @new-chat="handleNewChat" />
    </aside>

    <!-- 中央对话区 -->
    <div class="flex flex-1 flex-col overflow-hidden">
      <!-- 状态条 -->
      <ChatStatusBar
        :model-name="selectedModel"
        :session-id="chatStore.activeSessionId ?? undefined"
      />

      <!-- 消息列表 -->
      <div
        ref="messageListRef"
        class="flex-1 overflow-y-auto"
      >
        <WelcomePanel v-if="showWelcome" @quick-action="handleQuickAction" />
        <div v-else class="py-4 space-y-1">
          <div
            v-for="msg in chatStream.messages"
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

      <!-- 输入区 -->
      <InputArea
        :loading="chatStream.isLoading"
        @send="handleSend"
        @stop="chatStream.stop()"
      />
    </div>

    <!-- 右侧诊断面板 -->
    <DiagPanel :message="selectedDiagMessage" />
  </div>
</template>
