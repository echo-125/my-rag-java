<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue'
import { NButton } from 'naive-ui'
import { useChatStore } from '@/stores/chat'
import { useStreamChat } from '@/composables/useStreamChat'
import { useDiagnostics } from '@/composables/useDiagnostics'
import StatusBar from '@/components/layout/StatusBar.vue'
import SessionList from '@/components/chat/SessionList.vue'
import ChatWelcome from '@/components/chat/ChatWelcome.vue'
import AiCard from '@/components/chat/AiCard.vue'
import DiagPanel from '@/components/chat/DiagPanel.vue'

const store = useChatStore()
const { selectedMessageId, diagExpanded, selectMessage, toggleDiag, expandDiag, collapseDiag } = useDiagnostics()

const inputText = ref('')
const chatContainerRef = ref<HTMLDivElement>()
const chatScrollRef = ref<HTMLDivElement>()
const sessionColCollapsed = ref(false)

const {
  status, fullText, sources, toolMetadata, errorMessage,
  startTime, endTime, send, abort,
} = useStreamChat()

// 当前 AI 消息对象（流式过程中更新）
const currentAiId = ref<string | null>(null)

async function handleSend() {
  const query = inputText.value.trim()
  if (!query) return
  if (!store.selectedModelKey) {
    window.alert('请先选择或配置模型')
    return
  }

  inputText.value = ''
  store.isStreaming = true

  // 创建或使用当前会话
  if (!store.currentSessionId) {
    store.currentSessionId = crypto.randomUUID()
  }

  const userMsgId = crypto.randomUUID()
  store.addMessage({
    id: userMsgId,
    role: 'user',
    content: query,
    createdAt: new Date(),
    sessionId: store.currentSessionId,
    status: 'completed',
    sources: [],
    toolMetadata: [],
    duration: null,
  })

  const aiMsgId = crypto.randomUUID()
  currentAiId.value = aiMsgId
  store.addMessage({
    id: aiMsgId,
    role: 'assistant',
    content: '',
    createdAt: new Date(),
    sessionId: store.currentSessionId,
    status: 'streaming',
    sources: [],
    toolMetadata: [],
    duration: null,
    query,
    modelName: store.models.find(m => m.id === store.selectedModelKey)?.name || '',
  })

  selectMessage(aiMsgId)
  await nextTick()
  scrollToBottom()

  await send(query, store.selectedModelKey, store.currentSessionId)

  // 流式结束后更新消息状态
  store.updateMessage(aiMsgId, {
    content: fullText.value,
    status: status.value === 'completed' ? 'completed' : status.value === 'interrupted' ? 'interrupted' : 'error',
    sources: sources.value,
    toolMetadata: toolMetadata.value,
    duration: endTime.value > 0 ? endTime.value - startTime.value : null,
  })

  store.isStreaming = false
  await store.loadSessions()
}

function handleQuickQuestion(text: string) {
  inputText.value = text
  handleSend()
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

function scrollToBottom() {
  if (chatScrollRef.value) {
    chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
  }
}

// 监听流式文本变化，自动滚动
watch(fullText, () => {
  scrollToBottom()
})

function handleSelectMessage(id: string) {
  selectMessage(id)
  if (!diagExpanded.value) expandDiag()
}

onMounted(() => {
  store.loadSessions()
})
</script>

<template>
  <div class="flex-1 flex flex-col h-full overflow-hidden">
    <!-- 顶部状态条 -->
    <StatusBar />

    <!-- 三栏主体 -->
    <div class="flex-1 flex overflow-hidden relative">

      <!-- 左侧会话栏 -->
      <div
        class="flex flex-col flex-shrink-0 transition-all duration-300 overflow-hidden"
        :class="sessionColCollapsed ? 'w-0' : 'w-56'"
        style="background: var(--bg-elevated); border-right: 1px solid var(--border)"
      >
        <div class="p-2.5 flex items-center justify-between" style="border-bottom: 1px solid var(--border)">
          <span class="text-[10px] font-semibold uppercase tracking-wider" style="color: var(--text-4)">历史会话</span>
          <button @click="sessionColCollapsed = !sessionColCollapsed" class="text-xs p-0.5 rounded transition-colors" style="color: var(--text-4)">
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" /></svg>
          </button>
        </div>
        <SessionList />
      </div>

      <!-- 会话栏展开按钮 -->
      <button
        v-if="sessionColCollapsed"
        @click="sessionColCollapsed = false"
        class="w-5 h-10 flex items-center justify-center rounded-l text-xs flex-shrink-0"
        style="background: var(--bg-card); border: 1px solid var(--border); border-right: none; color: var(--text-3); z-index: 30; box-shadow: 2px 0 10px rgba(0,0,0,0.1)"
      >
        ▸
      </button>

      <!-- 中央对话区 -->
      <div ref="chatScrollRef" class="flex-1 overflow-y-auto scroll-smooth">
        <div ref="chatContainerRef" class="max-w-4xl mx-auto space-y-1 p-4">
          <ChatWelcome
            v-if="store.currentMessages.length === 0 && status === 'idle'"
            @quick-question="handleQuickQuestion"
          />

          <template v-for="msg in store.currentMessages" :key="msg.id">
            <!-- 用户消息 -->
            <div v-if="msg.role === 'user'" class="flex justify-end animate-slide-in mb-3">
              <div class="rounded-2xl px-5 py-3 max-w-[80%] shadow-sm" style="background: #f3f4f6; color: var(--text-1)">
                <div class="text-sm leading-relaxed whitespace-pre-wrap">{{ msg.content }}</div>
              </div>
            </div>

            <!-- AI 回答 -->
            <AiCard
              v-else
              :msg="msg"
              :selected="selectedMessageId === msg.id"
              @select="handleSelectMessage"
            />
          </template>
        </div>
      </div>

      <!-- 右侧诊断栏 -->
      <div
        class="flex-shrink-0 overflow-hidden transition-all duration-300 border-l"
        :class="diagExpanded ? 'w-80' : 'w-0'"
        style="background: var(--bg-elevated); border-color: var(--border)"
      >
        <DiagPanel />
      </div>

      <!-- 诊断栏折叠时的展开按钮 -->
      <button
        v-if="!diagExpanded && store.selectedMessageId"
        @click="toggleDiag"
        class="fixed right-0 top-1/2 -translate-y-1/2 w-5 h-10 flex items-center justify-center rounded-l text-xs"
        style="background: var(--bg-card); border: 1px solid var(--border); border-right: none; color: var(--text-3); z-index: 30"
      >
        ‹
      </button>
    </div>

    <!-- 底部输入区 -->
    <div class="px-4 py-3" style="background: var(--bg-elevated); border-top: 1px solid var(--border)">
      <div class="max-w-4xl mx-auto">
        <div class="flex items-end gap-2 rounded-lg px-3 py-2 transition-all" style="background: var(--bg-card); border: 1px solid var(--border)">
          <span class="font-mono font-bold text-sm leading-6" style="color: var(--c-primary)">&gt;</span>
          <textarea
            v-model="inputText"
            rows="1"
            placeholder="输入查询... (Enter 发送, Shift+Enter 换行)"
            class="flex-1 border-none outline-none resize-none text-[13px] leading-relaxed bg-transparent"
            style="color: var(--text-1); max-height: 160px"
            @keydown="handleKeydown"
          ></textarea>
          <button
            @click="handleSend"
            :disabled="status === 'streaming' || !inputText.trim()"
            class="flex items-center gap-1 px-3 py-1 rounded-md text-xs text-white flex-shrink-0 transition-colors"
            style="background: var(--c-primary)"
          >
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" /></svg>
            <span>执行</span>
          </button>
        </div>
        <div class="flex justify-between text-[10px] mt-1.5 px-1" style="color: var(--text-4)">
          <span>Enter 发送 · Shift+Enter 换行</span>
          <span class="font-mono">{{ inputText.length }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
