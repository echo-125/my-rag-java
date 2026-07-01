<script setup lang="ts">
defineProps<{
  percentage: number
  current: number
  total: number
  currentFile: string
  eta: string
  status: string
}>()
</script>

<template>
  <div class="bg-gray-50 p-5 rounded-xl border" style="border-color: var(--border)">
    <div class="flex items-center justify-between text-sm mb-3">
      <span class="font-medium" :class="status === 'completed' ? 'text-green-600' : 'text-gray-800'">
        {{ status === 'completed' ? '入库完成' : '处理中...' }}
      </span>
      <div class="flex items-center gap-4">
        <span class="text-xs bg-white px-2 py-1 rounded border shadow-sm" style="color: var(--text-3)">
          预计剩余: <span class="font-mono font-medium" style="color: var(--c-primary)">{{ eta }}</span>
        </span>
        <span class="font-bold" style="color: var(--c-primary)">{{ percentage }}%</span>
      </div>
    </div>
    <div class="w-full bg-gray-200 rounded-full h-2.5 overflow-hidden mb-3">
      <div
        class="h-2.5 rounded-full transition-all duration-300"
        :style="{ width: percentage + '%', background: 'var(--c-primary)' }"
      ></div>
    </div>
    <div class="flex justify-between text-xs" style="color: var(--text-3)">
      <span class="truncate max-w-[70%]" :title="currentFile">{{ currentFile || '—' }}</span>
      <span class="font-mono">{{ current }} / {{ total }}</span>
    </div>
  </div>
</template>
