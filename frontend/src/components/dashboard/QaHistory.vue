<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getRecentQa } from '@/api/dashboard'
import type { QaRecord } from '@/api/types/dashboard'
import { formatTime } from '@/utils/format'

const records = ref<QaRecord[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    records.value = await getRecentQa()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-4 space-y-3">
    <div v-if="loading" class="text-sm text-center py-8" style="color: var(--text-4)">加载中...</div>
    <div v-else-if="records.length === 0" class="text-sm text-center py-8" style="color: var(--text-4)">暂无记录</div>
    <div
      v-for="qa in records"
      :key="qa.id"
      class="p-3 rounded-lg animate-fade-in"
      style="background: var(--bg-elevated); border: 1px solid var(--border-subtle)"
    >
      <div class="text-sm font-medium truncate" style="color: var(--text-1)">{{ qa.question }}</div>
      <div class="text-xs mt-1 line-clamp-2" style="color: var(--text-3)">{{ qa.answer || '' }}</div>
      <div class="text-xs mt-1.5" style="color: var(--text-4)">
        {{ qa.modelName }} · {{ formatTime(qa.createdAt) }}
      </div>
    </div>
  </div>
</template>
