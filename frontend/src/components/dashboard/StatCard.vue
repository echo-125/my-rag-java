<script setup lang="ts">
defineProps<{
  title: string
  value: string | number
  icon?: string
  color?: string
}>()
</script>

<template>
  <div class="rounded-xl p-4 flex items-center gap-4 shadow-sm" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div
      class="w-12 h-12 rounded-lg flex items-center justify-center text-xl"
      :style="{ background: (color || 'var(--c-primary)') + '15', color: color || 'var(--c-primary)' }"
    >
      {{ icon || '📊' }}
    </div>
    <div>
      <p class="text-xs mb-0.5" style="color: var(--text-3)">{{ title }}</p>
      <p class="text-xl font-bold" style="color: var(--text-1)">{{ value }}</p>
    </div>
  </div>
</template>
