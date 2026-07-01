<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getEvalReport } from '@/api/dashboard'
import type { EvalReportBrief } from '@/api/types/dashboard'

const report = ref<EvalReportBrief | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    report.value = await getEvalReport()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="p-4">
    <div v-if="loading" class="text-sm text-center py-4" style="color: var(--text-4)">加载中...</div>
    <div v-else-if="!report || !report.found" class="text-sm text-center py-6" style="color: var(--text-4)">
      暂无评估报告
    </div>
    <template v-else>
      <div class="grid grid-cols-2 gap-3 mb-3">
        <div class="text-center p-2 rounded-lg" style="background: #eff6ff">
          <div class="text-lg font-bold" style="color: #2563eb">{{ ((report.precisionAtK || 0) * 100).toFixed(1) }}%</div>
          <div class="text-xs" style="color: var(--text-3)">Precision@K</div>
        </div>
        <div class="text-center p-2 rounded-lg" style="background: #f0fdf4">
          <div class="text-lg font-bold" style="color: #16a34a">{{ ((report.hitRate || 0) * 100).toFixed(1) }}%</div>
          <div class="text-xs" style="color: var(--text-3)">Hit Rate</div>
        </div>
        <div class="text-center p-2 rounded-lg" style="background: #faf5ff">
          <div class="text-lg font-bold" style="color: #9333ea">{{ report.mrr ? report.mrr.toFixed(3) : '—' }}</div>
          <div class="text-xs" style="color: var(--text-3)">MRR</div>
        </div>
        <div class="text-center p-2 rounded-lg" style="background: #fffbeb">
          <div class="text-lg font-bold" style="color: #d97706">{{ report.avgLatencyMs || '—' }}ms</div>
          <div class="text-xs" style="color: var(--text-3)">平均延迟</div>
        </div>
      </div>
      <div class="text-xs text-center" style="color: var(--text-4)">
        {{ report.completedCases }}/{{ report.totalCases }} 题 · {{ report.evaluatedAt ? new Date(report.evaluatedAt).toLocaleString() : '' }}
      </div>
    </template>
  </div>
</template>
