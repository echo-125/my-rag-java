<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getEvalHistory } from '@/api/evaluation'
import type { BatchSummary } from '@/api/types/evaluation'

const containerRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

async function loadAndRender() {
  try {
    const data = await getEvalHistory()
    if (!data || data.length === 0) {
      if (containerRef.value) containerRef.value.innerHTML = '<div class="text-sm text-center py-12" style="color: var(--text-4)">暂无历史数据</div>'
      return
    }

    await nextTick()
    if (!containerRef.value) return

    if (!chart) {
      chart = echarts.init(containerRef.value)
    }

    const sorted = [...data].sort((a, b) => (a.evaluatedAt || '').localeCompare(b.evaluatedAt || ''))
    const times = sorted.map(b => {
      if (!b.evaluatedAt) return '—'
      const d = new Date(b.evaluatedAt)
      return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
    })

    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { top: 10, textStyle: { fontSize: 11 } },
      grid: { left: 50, right: 20, top: 45, bottom: 30 },
      xAxis: { type: 'category', data: times, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10, formatter: (v: number) => v + '%' }, min: 0 },
      series: [
        { name: 'Precision@K', type: 'line', data: sorted.map(b => b.precisionAtK != null ? +(b.precisionAtK * 100).toFixed(1) : null), smooth: true, lineStyle: { width: 2 }, itemStyle: { color: '#2563eb' }, symbol: 'circle', symbolSize: 6 },
        { name: 'Recall', type: 'line', data: sorted.map(b => b.recall != null ? +(b.recall * 100).toFixed(1) : null), smooth: true, lineStyle: { width: 2 }, itemStyle: { color: '#16a34a' }, symbol: 'circle', symbolSize: 6 },
        { name: 'MRR', type: 'line', data: sorted.map(b => b.mrr != null ? +b.mrr.toFixed(3) : null), smooth: true, lineStyle: { width: 2 }, itemStyle: { color: '#9333ea' }, symbol: 'circle', symbolSize: 6 },
        { name: 'Hit Rate', type: 'line', data: sorted.map(b => b.hitRate != null ? +(b.hitRate * 100).toFixed(1) : null), smooth: true, lineStyle: { width: 2 }, itemStyle: { color: '#d97706' }, symbol: 'circle', symbolSize: 6 },
      ],
    })
  } catch {}
}

onMounted(() => {
  loadAndRender()
  window.addEventListener('resize', () => chart?.resize())
})
</script>

<template>
  <div class="rounded-xl shadow-sm overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: #6366f1"></div>
        历史趋势
      </h2>
    </div>
    <div ref="containerRef" class="w-full h-[280px]"></div>
  </div>
</template>
