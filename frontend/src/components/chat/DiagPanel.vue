<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useDiagnostics } from '@/composables/useDiagnostics'

const store = useChatStore()
const { getConclusions, getToolName, getToolIcon, copyText } = useDiagnostics()

const selectedMessage = computed<ChatMessage | null>(() => {
  if (!store.selectedMessageId) return null
  return store.messages.get(store.selectedMessageId) || null
})
</script>

<template>
  <div class="w-80 p-3 overflow-y-auto">
    <template v-if="selectedMessage">
      <!-- 请求快照 -->
      <div class="mb-3">
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">请求快照</div>
        <div class="flex justify-between text-xs py-0.5"><span style="color: var(--text-4)">问题</span><span class="text-right max-w-[60%] truncate" :title="selectedMessage.query || selectedMessage.content" style="color: var(--text-2)">{{ (selectedMessage.query || selectedMessage.content || '').substring(0, 60) }}</span></div>
        <div class="flex justify-between text-xs py-0.5"><span style="color: var(--text-4)">模型</span><span style="color: var(--text-2)">{{ selectedMessage.modelName || '—' }}</span></div>
        <div class="flex justify-between text-xs py-0.5"><span style="color: var(--text-4)">时间</span><span style="color: var(--text-2)">{{ selectedMessage.createdAt.toLocaleString('zh-CN') }}</span></div>
      </div>

      <!-- 回答统计 -->
      <div class="mb-3">
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">回答统计</div>
        <div class="grid grid-cols-2 gap-1.5">
          <div class="text-center p-1.5 rounded" style="background: var(--bg-elevated)">
            <div class="text-sm font-bold font-mono" style="color: var(--text-1)">{{ selectedMessage.content.length }}</div>
            <div class="text-[9px] uppercase" style="color: var(--text-4)">字符数</div>
          </div>
          <div class="text-center p-1.5 rounded" style="background: var(--bg-elevated)">
            <div class="text-sm font-bold font-mono" style="color: var(--text-1)">{{ selectedMessage.sources.length }}</div>
            <div class="text-[9px] uppercase" style="color: var(--text-4)">引用数</div>
          </div>
          <div class="text-center p-1.5 rounded" style="background: var(--bg-elevated)">
            <div class="text-sm font-bold font-mono" style="color: var(--text-1)">{{ selectedMessage.toolMetadata.length }}</div>
            <div class="text-[9px] uppercase" style="color: var(--text-4)">工具数</div>
          </div>
          <div class="text-center p-1.5 rounded" style="background: var(--bg-elevated)">
            <div class="text-sm font-bold font-mono" style="color: var(--text-1)">{{ selectedMessage.duration != null ? (selectedMessage.duration / 1000).toFixed(1) + 's' : '—' }}</div>
            <div class="text-[9px] uppercase" style="color: var(--text-4)">耗时</div>
          </div>
        </div>
      </div>

      <!-- 引用来源 -->
      <div class="mb-3">
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">引用来源</div>
        <div v-if="selectedMessage.sources.length === 0" class="text-[11px] italic" style="color: var(--text-4)">无引用数据</div>
        <div v-for="(s, i) in selectedMessage.sources" :key="i" class="flex gap-1.5 py-1 text-[11px]" style="border-bottom: 1px solid var(--border-subtle)">
          <span class="font-mono" style="color: var(--text-4)">[{{ i + 1 }}]</span>
          <div><div class="font-medium" style="color: var(--text-2)">{{ s.name }}</div><div class="font-mono text-[9px]" style="color: var(--text-4)">{{ s.path }}</div></div>
        </div>
      </div>

      <!-- 工具调用 -->
      <div class="mb-3">
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">工具调用流水</div>
        <div v-if="selectedMessage.toolMetadata.length === 0" class="text-[11px] italic" style="color: var(--text-4)">无工具调用</div>
        <div v-for="(t, i) in selectedMessage.toolMetadata" :key="i" class="flex justify-between items-center py-0.5 text-[11px]">
          <span>{{ getToolIcon(t.tool) }} {{ getToolName(t.tool) }}</span>
          <span class="font-mono" style="color: var(--text-3)">{{ t.duration }}ms</span>
        </div>
      </div>

      <!-- 诊断结论 -->
      <div class="mb-3">
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">诊断结论</div>
        <ul class="space-y-0.5">
          <li v-for="(c, i) in getConclusions(selectedMessage)" :key="i" class="text-[11px] pl-3 relative" style="color: var(--text-2)">
            <span class="absolute left-0" style="color: var(--c-primary)">›</span>{{ c }}
          </li>
        </ul>
      </div>

      <!-- 快速操作 -->
      <div>
        <div class="text-[10px] font-semibold uppercase tracking-wider mb-1.5 pb-1" style="color: var(--text-3); border-bottom: 1px solid var(--border)">快速操作</div>
        <div class="flex gap-1.5 flex-wrap">
          <button class="text-[10px] px-2 py-0.5 rounded transition-colors" style="border: 1px solid var(--border); color: var(--text-2)" @click="copyText(selectedMessage.query || '')">复制问题</button>
          <button class="text-[10px] px-2 py-0.5 rounded transition-colors" style="border: 1px solid var(--border); color: var(--text-2)" @click="copyText(selectedMessage.content)">复制回答</button>
        </div>
      </div>
    </template>

    <div v-else class="text-xs text-center py-8" style="color: var(--text-4)">选中一条 AI 回复查看详情</div>
  </div>
</template>
