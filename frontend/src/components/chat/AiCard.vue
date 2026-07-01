<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import { renderMarkdown } from '@/composables/useMarkdown'
import { renderMermaidInContainer } from '@/composables/useMermaid'
import CitationList from './CitationList.vue'
import ToolCallList from './ToolCallList.vue'
import FeedbackBar from './FeedbackBar.vue'

const props = defineProps<{
  msg: ChatMessage
  selected: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
}>()

const contentRef = ref<HTMLDivElement>()
const showSources = ref(false)
const showTools = ref(false)

// 流式渲染 Markdown + Mermaid
watch(() => props.msg.content, async (text) => {
  if (!contentRef.value || !text) return
  await nextTick()
  contentRef.value.innerHTML = renderMarkdown(text)
  if (props.msg.status === 'completed') {
    await renderMermaidInContainer(contentRef.value)
  }
}, { immediate: true })

function toggleSources() { showSources.value = !showSources.value }
function toggleTools() { showTools.value = !showTools.value }
</script>

<template>
  <div
    class="rounded-lg p-3 transition-all cursor-pointer mb-3"
    :class="selected ? '' : ''"
    :style="{
      border: selected ? '1px solid var(--c-primary)' : '1px solid var(--border)',
      boxShadow: selected ? '0 0 0 1px var(--c-primary)' : 'none',
    }"
    @click="emit('select', msg.id)"
  >
    <!-- 工具调用（可折叠） -->
    <div v-if="msg.toolMetadata.length > 0" class="mb-2" style="border-top: 1px solid var(--border-subtle); padding-top: 8px">
      <div class="flex items-center gap-2 text-[11px] cursor-pointer" style="color: var(--text-3)" @click.stop="toggleTools">
        <span>🔧 工具调用 ({{ msg.toolMetadata.length }})</span>
        <span class="ml-auto text-[10px]">{{ showTools ? '▼' : '▶' }}</span>
      </div>
      <ToolCallList v-if="showTools" :tools="msg.toolMetadata" />
    </div>

    <!-- AI 回答内容 -->
    <div ref="contentRef" class="prose prose-sm max-w-none" style="color: var(--text-2)">
      <p v-if="msg.status === 'streaming' && !msg.content" class="flex items-center gap-2" style="color: var(--text-4)">
        <span class="w-2 h-2 rounded-full animate-ping" style="background: var(--c-primary)"></span>
        思考并检索中...
      </p>
    </div>

    <!-- 元信息条 -->
    <div class="flex gap-1.5 flex-wrap mt-2">
      <span v-if="msg.sources.length > 0" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #f0fdf4; color: #166534">
        📎 引用 {{ msg.sources.length }}
      </span>
      <span v-if="msg.toolMetadata.length > 0" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #eff6ff; color: #1e40af">
        🔧 工具 {{ msg.toolMetadata.length }}
      </span>
      <span v-if="msg.duration != null" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #fefce8; color: #854d0e">
        ⏱ {{ (msg.duration / 1000).toFixed(1) }}s
      </span>
      <span v-if="msg.status === 'completed'" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #f0fdf4; color: #166534">✓ 完成</span>
      <span v-else-if="msg.status === 'error'" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #fef2f2; color: #991b1b">✕ 异常</span>
      <span v-else-if="msg.status === 'streaming'" class="text-[10px] px-1.5 py-0.5 rounded font-mono" style="background: #eff6ff; color: #2563eb">● 流式中</span>
    </div>

    <!-- 引用来源（可折叠） -->
    <div v-if="msg.sources.length > 0" class="mt-2" style="border-top: 1px solid var(--border-subtle); padding-top: 8px">
      <div class="flex items-center gap-2 text-[11px] cursor-pointer" style="color: var(--text-3)" @click.stop="toggleSources">
        <span>📎 引用来源 ({{ msg.sources.length }})</span>
        <span class="ml-auto text-[10px]">{{ showSources ? '▼' : '▶' }}</span>
      </div>
      <CitationList v-if="showSources" :sources="msg.sources" />
    </div>

    <!-- 反馈栏 -->
    <FeedbackBar v-if="msg.status === 'completed' && msg.role === 'assistant'" :query="msg.query || ''" :answer="msg.content" />
  </div>
</template>
