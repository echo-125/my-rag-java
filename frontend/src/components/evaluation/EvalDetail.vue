<script setup lang="ts">
import type { EvalResult } from '@/api/types/evaluation'

defineProps<{ results: EvalResult[] }>()

function safeParse(json: string): string[] {
  try { return JSON.parse(json || '[]') } catch { return [] }
}
</script>

<template>
  <div class="rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: var(--text-3)"></div>
        逐题明细
      </h2>
    </div>

    <div class="overflow-y-auto flex-1">
      <div v-if="results.length === 0" class="text-sm text-center py-12" style="color: var(--text-4)">暂无评估结果</div>
      <table v-else class="w-full text-xs">
        <thead style="background: var(--bg-elevated); color: var(--text-3); position: sticky; top: 0">
          <tr style="border-bottom: 1px solid var(--border-subtle)">
            <th class="px-4 py-2 text-left font-medium">问题</th>
            <th class="px-4 py-2 text-center font-medium w-16">命中</th>
            <th class="px-4 py-2 text-center font-medium w-16">排名</th>
            <th class="px-4 py-2 text-center font-medium w-20">耗时</th>
            <th class="px-4 py-2 text-left font-medium">期望文件</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(r, i) in results"
            :key="i"
            class="transition-colors"
            :style="{ background: r.parseWarning ? 'rgba(254,226,226,0.3)' : 'transparent' }"
          >
            <td class="px-4 py-2.5 max-w-[200px] truncate" :title="r.question" style="color: var(--text-1)">
              {{ r.question }}
              <span v-if="r.parseWarning" class="text-red-500 text-[10px]" :title="r.parseWarning">⚠</span>
            </td>
            <td class="px-4 py-2.5 text-center">
              <span v-if="r.hit" style="color: #16a34a">✓</span>
              <span v-else style="color: #f87171">✗</span>
            </td>
            <td class="px-4 py-2.5 text-center" style="color: var(--text-2)">{{ r.firstHitRank || '—' }}</td>
            <td class="px-4 py-2.5 text-center" style="color: var(--text-3)">{{ r.latencyMs || '—' }}ms</td>
            <td class="px-4 py-2.5 max-w-[200px] truncate" :title="safeParse(r.expectedFiles).join(', ')" style="color: var(--text-3)">
              {{ safeParse(r.expectedFiles).map(f => f.split('/').pop()).join(', ') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
