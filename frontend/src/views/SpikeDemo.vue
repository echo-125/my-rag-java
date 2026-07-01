<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { useStreamChat } from '@/composables/useStreamChat'
import { renderMarkdown } from '@/composables/useMarkdown'
import { renderMermaidInContainer } from '@/composables/useMermaid'
import { useDiagnostics } from '@/composables/useDiagnostics'
import type { ChatMessage } from '@/stores/chat'

const query = ref('')
const modelKey = ref('')
const sessionId = ref<string | null>(null)
const contentRef = ref<HTMLDivElement>()

const {
  status, fullText, sources, toolMetadata, errorMessage,
  startTime, endTime, send, abort,
} = useStreamChat()

const {
  selectedMessageId, diagExpanded, selectMessage, toggleDiag,
  getConclusions, getToolName, getToolIcon, copyText,
} = useDiagnostics()

// 模拟消息对象（用于诊断面板）
const currentMessage = ref<ChatMessage | null>(null)

watch(fullText, async (text) => {
  await nextTick()
  if (contentRef.value) {
    contentRef.value.innerHTML = renderMarkdown(text)
    await renderMermaidInContainer(contentRef.value)
  }
})

watch([sources, toolMetadata, status], () => {
  currentMessage.value = {
    id: 'spike-1',
    role: 'assistant',
    content: fullText.value,
    createdAt: new Date(),
    sessionId: sessionId.value,
    status: status.value,
    sources: sources.value,
    toolMetadata: toolMetadata.value,
    duration: endTime.value > 0 ? endTime.value - startTime.value : null,
    query: query.value,
    modelName: '',
  }
})

async function handleSend() {
  if (!query.value.trim()) return
  selectMessage('spike-1')
  await send(query.value, modelKey.value, sessionId.value)
}

// ─── TerminalLog 性能测试 ───
const logTestCount = ref(0)
const logTestRunning = ref(false)
const logs = ref<Array<{ type: string; message: string; time: string }>>([])
const logContainerRef = ref<HTMLDivElement>()

function addTestLog(type: string, message: string) {
  const time = new Date().toLocaleTimeString('en-US', { hour12: false })
  logs.value.push({ type, message, time })
  if (logs.value.length > 2000) {
    logs.value.splice(0, logs.value.length - 2000)
  }
}

async function runLogTest(count: number) {
  logTestRunning.value = true
  logTestCount.value = count
  logs.value = []
  const start = performance.now()
  for (let i = 0; i < count; i++) {
    addTestLog('processing', `处理文件 ${i + 1}/${count}: src/main/java/com/he/service/RagChatService.java`)
    await new Promise(r => setTimeout(r, 5))
  }
  const elapsed = performance.now() - start
  addTestLog('done', `完成！${count} 条日志，耗时 ${elapsed.toFixed(0)}ms`)
  logTestRunning.value = false
}

// 自动滚动日志
watch(logs, async () => {
  await nextTick()
  if (logContainerRef.value) {
    logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
  }
}, { deep: true })
</script>

<template>
  <div class="flex h-screen overflow-hidden" style="background: var(--bg-page)">
    <!-- 主区域 -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- 顶部标题 -->
      <div class="px-4 py-2 border-b flex items-center gap-2" style="border-color: var(--border); background: var(--bg-card)">
        <span class="text-sm font-semibold" style="color: var(--text-1)">Spike 验证</span>
        <span class="text-xs px-2 py-0.5 rounded" style="background: var(--bg-elevated); color: var(--text-3)">
          聊天核心链路 + Mermaid + 诊断 + TerminalLog 性能
        </span>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-y-auto p-6">
        <div class="max-w-4xl mx-auto space-y-6">

          <!-- 输入区 -->
          <div class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h3 class="text-sm font-semibold mb-3" style="color: var(--text-1)">测试输入</h3>
            <div class="space-y-3">
              <input v-model="query" placeholder="输入测试问题..." class="w-full px-3 py-2 rounded-lg text-sm outline-none" style="border: 1px solid var(--border)" />
              <input v-model="modelKey" placeholder="模型 Key（留空用默认）" class="w-full px-3 py-2 rounded-lg text-sm outline-none" style="border: 1px solid var(--border)" />
              <div class="flex gap-2">
                <button @click="handleSend" :disabled="status === 'streaming'" class="px-4 py-2 rounded-lg text-sm text-white" style="background: var(--c-primary)">
                  {{ status === 'streaming' ? '流式中...' : '发送' }}
                </button>
                <button v-if="status === 'streaming'" @click="abort" class="px-4 py-2 rounded-lg text-sm" style="border: 1px solid var(--border)">中断</button>
                <span class="text-xs self-center" style="color: var(--text-3)">
                  状态: {{ status }} | 耗时: {{ endTime > 0 ? ((endTime - startTime) / 1000).toFixed(1) + 's' : '—' }}
                </span>
              </div>
            </div>
          </div>

          <!-- 错误/中断提示 -->
          <div v-if="errorMessage" class="rounded-xl p-3 text-sm" style="background: #fef2f2; border: 1px solid #fecaca; color: #991b1b">
            {{ errorMessage }}
          </div>

          <!-- 渲染结果 -->
          <div class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h3 class="text-sm font-semibold mb-3" style="color: var(--text-1)">渲染结果（Markdown + Mermaid + DOMPurify）</h3>
            <div ref="contentRef" class="prose prose-sm max-w-none" style="color: var(--text-2)">
              <p v-if="!fullText" style="color: var(--text-4)">等待输入...</p>
            </div>
          </div>

          <!-- 引用来源 -->
          <div v-if="sources.length > 0" class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h3 class="text-sm font-semibold mb-2" style="color: var(--text-1)">引用来源 ({{ sources.length }})</h3>
            <div v-for="(s, i) in sources" :key="i" class="text-xs py-1" style="color: var(--text-3)">
              [{{ i + 1 }}] {{ s.name }} — {{ s.path }}
            </div>
          </div>

          <!-- 工具调用 -->
          <div v-if="toolMetadata.length > 0" class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h3 class="text-sm font-semibold mb-2" style="color: var(--text-1)">工具调用 ({{ toolMetadata.length }})</h3>
            <div v-for="(t, i) in toolMetadata" :key="i" class="text-xs py-1 flex gap-2" style="color: var(--text-3)">
              <span>{{ getToolIcon(t.tool) }} {{ getToolName(t.tool) }}</span>
              <span style="color: var(--text-4); font-family: var(--font-mono)">{{ t.duration }}ms</span>
              <span style="color: var(--text-4)">{{ t.args }}</span>
            </div>
          </div>

          <!-- TerminalLog 性能测试 -->
          <div class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h3 class="text-sm font-semibold mb-3" style="color: var(--text-1)">TerminalLog 性能测试</h3>
            <div class="flex gap-2 mb-3">
              <button @click="runLogTest(100)" :disabled="logTestRunning" class="px-3 py-1.5 rounded text-xs" style="border: 1px solid var(--border)">100 行</button>
              <button @click="runLogTest(500)" :disabled="logTestRunning" class="px-3 py-1.5 rounded text-xs" style="border: 1px solid var(--border)">500 行</button>
              <button @click="runLogTest(1000)" :disabled="logTestRunning" class="px-3 py-1.5 rounded text-xs" style="border: 1px solid var(--border)">1000 行</button>
            </div>
            <div ref="logContainerRef" class="h-48 overflow-y-auto rounded p-3 font-mono text-xs" style="background: var(--bg-terminal); color: #e5e7eb">
              <div v-for="(log, i) in logs" :key="i" :class="{
                'text-blue-400': log.type === 'info',
                'text-gray-300': log.type === 'processing',
                'text-red-400': log.type === 'error',
                'text-green-400': log.type === 'done',
              }">
                [{{ log.time }}] {{ log.message }}
              </div>
              <div v-if="logs.length === 0" class="text-gray-500">点击按钮开始测试</div>
            </div>
          </div>

        </div>
      </div>
    </div>

    <!-- 诊断侧栏 -->
    <div
      class="flex-shrink-0 overflow-hidden transition-all duration-300 border-l"
      :class="diagExpanded ? 'w-80' : 'w-0'"
      style="background: var(--bg-elevated); border-color: var(--border)"
    >
      <div class="w-80 p-3">
        <div class="flex items-center justify-between mb-3">
          <span class="text-xs font-semibold" style="color: var(--text-4)">诊断</span>
          <button @click="toggleDiag" class="text-xs" style="color: var(--text-4)">收起</button>
        </div>

        <div v-if="currentMessage" class="space-y-3">
          <!-- 请求快照 -->
          <div class="text-xs">
            <div class="font-semibold mb-1" style="color: var(--text-3)">请求快照</div>
            <div class="flex justify-between py-0.5"><span style="color: var(--text-4)">问题</span><span style="color: var(--text-2)">{{ (currentMessage.query || '').substring(0, 60) }}</span></div>
            <div class="flex justify-between py-0.5"><span style="color: var(--text-4)">模型</span><span style="color: var(--text-2)">{{ currentMessage.modelName || '—' }}</span></div>
            <div class="flex justify-between py-0.5"><span style="color: var(--text-4)">状态</span><span style="color: var(--text-2)">{{ currentMessage.status }}</span></div>
          </div>

          <!-- 回答统计 -->
          <div class="text-xs">
            <div class="font-semibold mb-1" style="color: var(--text-3)">回答统计</div>
            <div class="grid grid-cols-2 gap-2">
              <div class="text-center p-2 rounded" style="background: var(--bg-card)">
                <div class="text-base font-bold" style="color: var(--text-1)">{{ currentMessage.content.length }}</div>
                <div class="text-[10px]" style="color: var(--text-4)">字符数</div>
              </div>
              <div class="text-center p-2 rounded" style="background: var(--bg-card)">
                <div class="text-base font-bold" style="color: var(--text-1)">{{ currentMessage.sources.length }}</div>
                <div class="text-[10px]" style="color: var(--text-4)">引用数</div>
              </div>
              <div class="text-center p-2 rounded" style="background: var(--bg-card)">
                <div class="text-base font-bold" style="color: var(--text-1)">{{ currentMessage.toolMetadata.length }}</div>
                <div class="text-[10px]" style="color: var(--text-4)">工具数</div>
              </div>
              <div class="text-center p-2 rounded" style="background: var(--bg-card)">
                <div class="text-base font-bold" style="color: var(--text-1)">{{ currentMessage.duration != null ? (currentMessage.duration / 1000).toFixed(1) + 's' : '—' }}</div>
                <div class="text-[10px]" style="color: var(--text-4)">耗时</div>
              </div>
            </div>
          </div>

          <!-- 诊断结论 -->
          <div class="text-xs">
            <div class="font-semibold mb-1" style="color: var(--text-3)">诊断结论</div>
            <ul class="space-y-1">
              <li v-for="(c, i) in getConclusions(currentMessage)" :key="i" class="pl-3 relative" style="color: var(--text-2)">
                <span class="absolute left-0" style="color: var(--c-primary)">›</span>{{ c }}
              </li>
            </ul>
          </div>
        </div>

        <div v-else class="text-xs text-center py-8" style="color: var(--text-4)">
          发送消息后查看详情
        </div>
      </div>
    </div>

    <!-- 展开按钮（诊断栏折叠时显示） -->
    <button
      v-if="!diagExpanded && currentMessage"
      @click="toggleDiag"
      class="fixed right-0 top-1/2 -translate-y-1/2 w-5 h-10 flex items-center justify-center rounded-l text-xs"
      style="background: var(--bg-card); border: 1px solid var(--border); border-right: none; color: var(--text-3); z-index: 30"
    >
      ‹
    </button>
  </div>
</template>
