<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useChart } from '@/composables/useChart'
import { getLanguageStats } from '@/api/dashboard'
import type { LanguageStat } from '@/api/types/dashboard'

const containerRef = ref<HTMLElement | null>(null)
const { setOption, isReady } = useChart(containerRef)

async function loadData() {
  try {
    const stats = await getLanguageStats()
    if (stats.length > 0) {
      setOption({
        tooltip: {
          trigger: 'axis',
          backgroundColor: 'rgba(15,23,42,0.9)',
          borderColor: '#e5e7eb',
          textStyle: { color: '#f8fafc', fontSize: 12 },
        },
        grid: { left: 48, right: 24, top: 32, bottom: 48 },
        xAxis: {
          type: 'category',
          data: stats.map(s => s.language || 'unknown'),
          axisLabel: { color: '#6b7280', rotate: 30, fontSize: 11 },
          axisLine: { lineStyle: { color: '#e5e7eb' } },
        },
        yAxis: {
          type: 'value',
          name: 'Chunks',
          nameTextStyle: { color: '#9ca3af' },
          axisLabel: { color: '#6b7280' },
          splitLine: { lineStyle: { color: '#f3f4f6' } },
        },
        series: [{
          type: 'bar',
          data: stats.map(s => s.count),
          itemStyle: { color: 'var(--c-primary)', borderRadius: [4, 4, 0, 0] },
          barWidth: '40%',
        }],
      })
    } else {
      showEmpty()
    }
  } catch {
    showEmpty()
  }
}

function showEmpty() {
  setOption({
    backgroundColor: 'transparent',
    grid: { left: 48, right: 24, top: 32, bottom: 48 },
    xAxis: {
      type: 'category',
      data: ['Java', 'JavaScript', 'Markdown', 'Python', 'XML'],
      axisLabel: { color: '#9ca3af', rotate: 30, fontSize: 11 },
      axisLine: { lineStyle: { color: '#e5e7eb' } },
    },
    yAxis: {
      type: 'value',
      name: 'Chunks',
      nameTextStyle: { color: '#9ca3af' },
      axisLabel: { color: '#9ca3af' },
      splitLine: { lineStyle: { color: '#f3f4f6' } },
    },
    series: [{
      type: 'bar',
      data: [0, 0, 0, 0, 0],
      itemStyle: { color: '#e5e7eb', borderRadius: [4, 4, 0, 0] },
      barWidth: '40%',
    }],
  })
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div ref="containerRef" class="w-full min-h-[250px]"></div>
</template>
