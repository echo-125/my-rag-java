<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { LogEntry } from '@/composables/useStreamIngest'

const props = defineProps<{
  logs: LogEntry[]
}>()

const containerRef = ref<HTMLDivElement>()

const logColors: Record<string, string> = {
  info: '#60a5fa',
  processing: '#d1d5db',
  error: '#f87171',
  done: '#4ade80',
}

// 自动滚动到底部
watch(() => props.logs.length, async () => {
  await nextTick()
  if (containerRef.value) {
    containerRef.value.scrollTop = containerRef.value.scrollHeight
  }
})
</script>

<template>
  <div
    ref="containerRef"
    class="flex-1 overflow-y-auto p-4 font-mono text-xs"
    style="background: var(--bg-terminal); color: #d1d5db"
  >
    <div
      v-for="(log, i) in logs"
      :key="i"
      :style="{ color: logColors[log.type] || '#d1d5db' }"
    >
      [{{ log.time }}] {{ log.message }}
    </div>
    <div v-if="logs.length === 0" style="color: #6b7280">等待入库...</div>
  </div>
</template>
