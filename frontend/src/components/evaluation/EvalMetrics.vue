<script setup lang="ts">
defineProps<{
  precisionAtK: number | null
  recall: number | null
  mrr: number | null
  hitRate: number | null
}>()
</script>

<template>
  <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
    <div class="rounded-xl p-4 text-center" style="background: var(--bg-card); border: 1px solid var(--border)">
      <div class="text-2xl font-bold" style="color: #2563eb">{{ precisionAtK != null ? (precisionAtK * 100).toFixed(1) + '%' : '—' }}</div>
      <div class="text-xs mt-1" style="color: var(--text-3)">Precision@K</div>
    </div>
    <div class="rounded-xl p-4 text-center" style="background: var(--bg-card); border: 1px solid var(--border)">
      <div class="text-2xl font-bold" style="color: #16a34a">{{ recall != null ? (recall * 100).toFixed(1) + '%' : '—' }}</div>
      <div class="text-xs mt-1" style="color: var(--text-3)">Recall</div>
    </div>
    <div class="rounded-xl p-4 text-center" style="background: var(--bg-card); border: 1px solid var(--border)">
      <div class="text-2xl font-bold" style="color: #9333ea">{{ mrr != null ? mrr.toFixed(3) : '—' }}</div>
      <div class="text-xs mt-1" style="color: var(--text-3)">MRR</div>
    </div>
    <div class="rounded-xl p-4 text-center" style="background: var(--bg-card); border: 1px solid var(--border)">
      <div class="text-2xl font-bold" style="color: #d97706">{{ hitRate != null ? (hitRate * 100).toFixed(1) + '%' : '—' }}</div>
      <div class="text-xs mt-1" style="color: var(--text-3)">Hit Rate</div>
    </div>
  </div>
</template>
