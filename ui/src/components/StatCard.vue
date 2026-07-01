<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  icon?: string
  trend?: 'up' | 'down' | 'neutral'
  trendValue?: string
}>()
</script>

<template>
  <div class="rounded-xl border border-default bg-elevated p-4 transition-colors hover:border-primary/30">
    <div class="flex items-center justify-between">
      <span class="text-xs font-medium text-muted uppercase tracking-wider">{{ label }}</span>
      <UIcon v-if="icon" :name="icon" class="h-4 w-4 text-muted" />
    </div>
    <div class="mt-2 text-2xl font-bold font-mono tabular-nums text-default">
      {{ value }}
    </div>
    <div v-if="trend && trendValue" class="mt-1 flex items-center gap-1 text-xs">
      <UIcon
        :name="trend === 'up' ? 'lucide:trending-up' : trend === 'down' ? 'lucide:trending-down' : 'lucide:minus'"
        :class="trend === 'up' ? 'text-primary' : trend === 'down' ? 'text-red-400' : 'text-muted'"
      />
      <span :class="trend === 'up' ? 'text-primary' : trend === 'down' ? 'text-red-400' : 'text-muted'">
        {{ trendValue }}
      </span>
    </div>
  </div>
</template>
