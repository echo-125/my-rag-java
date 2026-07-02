<script setup lang="ts">
import { ref, computed } from 'vue'
import type { StreamMessage } from '@/composables/useChatStream'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { formatDuration } from '@/utils/format'

const props = defineProps<{
  message: StreamMessage
}>()

const emit = defineEmits<{
  feedback: [messageId: string, positive: boolean]
}>()

const expandedTools = ref(false)
const expandedSources = ref(false)

const totalToolTime = computed(() => {
  return (props.message.toolMetadata ?? []).reduce((s, t) => s + (t.durationMs ?? 0), 0)
})
</script>

<template>
  <div class="flex gap-3 px-4 py-2 group">
    <div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/20 text-xs font-bold text-primary">
      AI
    </div>
    <div class="max-w-[80%] min-w-0 flex flex-col gap-2">
      <!-- 工具调用区 -->
      <div
        v-if="message.toolMetadata && message.toolMetadata.length > 0"
        class="rounded-lg border border-default bg-elevated overflow-hidden"
      >
        <button
          class="flex w-full items-center justify-between px-3 py-1.5 text-xs text-muted hover:bg-muted/10 transition-colors"
          @click="expandedTools = !expandedTools"
        >
          <span class="flex items-center gap-1.5">
            <UIcon name="lucide:wrench" class="h-3 w-3" />
            {{ message.toolMetadata.length }} 个工具调用 · {{ formatDuration(totalToolTime) }}
          </span>
          <UIcon
            :name="expandedTools ? 'lucide:chevron-up' : 'lucide:chevron-down'"
            class="h-3 w-3"
          />
        </button>
        <Transition name="slide">
          <div v-if="expandedTools" class="border-t border-default px-3 py-2 space-y-1">
            <div
              v-for="(tool, i) in message.toolMetadata"
              :key="i"
              class="flex items-center justify-between text-xs font-mono"
            >
              <span class="text-primary">{{ tool.toolName }}</span>
              <span class="text-muted">{{ formatDuration(tool.durationMs) }}</span>
            </div>
          </div>
        </Transition>
      </div>

      <!-- Markdown 内容区 -->
      <div class="relative">
        <MarkdownRenderer
          v-if="message.content"
          :content="message.content"
          class="text-sm"
        />
        <span v-if="message.isStreaming" class="typing-cursor" />
        <div v-if="!message.content && message.isStreaming" class="flex items-center gap-2 text-sm text-muted py-2">
          <UIcon name="lucide:loader-2" class="h-4 w-4 animate-spin" />
          正在思考...
        </div>
      </div>

      <!-- 元信息条 -->
      <div v-if="!message.isStreaming && message.content" class="flex items-center gap-2 text-[10px] text-muted">
        <span
          v-if="message.sources && message.sources.length > 0"
          class="rounded bg-elevated px-1.5 py-0.5 font-mono"
        >
          {{ message.sources.length }} 引用
        </span>
        <span
          v-if="message.toolMetadata && message.toolMetadata.length > 0"
          class="rounded bg-elevated px-1.5 py-0.5 font-mono"
        >
          {{ message.toolMetadata.length }} 工具
        </span>
        <span
          v-if="totalToolTime > 0"
          class="rounded bg-elevated px-1.5 py-0.5 font-mono"
        >
          {{ formatDuration(totalToolTime) }}
        </span>
      </div>

      <!-- 引用来源区 -->
      <div
        v-if="message.sources && message.sources.length > 0 && !message.isStreaming"
        class="rounded-lg border border-default bg-elevated overflow-hidden"
      >
        <button
          class="flex w-full items-center justify-between px-3 py-1.5 text-xs text-muted hover:bg-muted/10 transition-colors"
          @click="expandedSources = !expandedSources"
        >
          <span class="flex items-center gap-1.5">
            <UIcon name="lucide:file-text" class="h-3 w-3" />
            引用来源 ({{ message.sources.length }})
          </span>
          <UIcon
            :name="expandedSources ? 'lucide:chevron-up' : 'lucide:chevron-down'"
            class="h-3 w-3"
          />
        </button>
        <Transition name="slide">
          <div v-if="expandedSources" class="border-t border-default px-3 py-2 space-y-1">
            <div
              v-for="(src, i) in message.sources"
              :key="i"
              class="flex items-center justify-between text-xs"
            >
              <span class="font-mono text-default truncate">
                <UIcon name="lucide:file" class="h-3 w-3 inline mr-1 text-muted" />
                {{ src.fileName }}
              </span>
              <span class="text-muted font-mono shrink-0 ml-2">
                {{ (src.score * 100).toFixed(1) }}%
              </span>
            </div>
          </div>
        </Transition>
      </div>

      <!-- 反馈栏 -->
      <div v-if="!message.isStreaming && message.content" class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        <UButton
          icon="lucide:thumbs-up"
          size="xs"
          color="neutral"
          variant="ghost"
          @click="emit('feedback', message.id, true)"
        />
        <UButton
          icon="lucide:thumbs-down"
          size="xs"
          color="neutral"
          variant="ghost"
          @click="emit('feedback', message.id, false)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.slide-enter-active, .slide-leave-active {
  transition: all 0.2s ease;
  overflow: hidden;
}
.slide-enter-from, .slide-leave-to {
  max-height: 0;
  opacity: 0;
}
.slide-enter-to, .slide-leave-from {
  max-height: 500px;
  opacity: 1;
}
</style>
