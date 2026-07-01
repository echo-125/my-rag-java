<script setup lang="ts">
defineProps<{
  active: boolean
  activeLabel?: string
  inactiveLabel?: string
}>()
</script>

<template>
  <span
    class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium"
    :class="active
      ? 'bg-primary/15 text-primary'
      : 'bg-neutral/15 text-muted'"
  >
    <span
      class="h-1.5 w-1.5 rounded-full"
      :class="active ? 'bg-primary animate-pulse-green' : 'bg-muted'"
    />
    {{ active ? (activeLabel ?? 'ON') : (inactiveLabel ?? 'OFF') }}
  </span>
</template>
