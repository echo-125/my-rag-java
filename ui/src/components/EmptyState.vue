<script setup lang="ts">
defineProps<{
  icon?: string
  title: string
  description?: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-16 text-center">
    <div class="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-elevated">
      <UIcon :name="icon ?? 'lucide:inbox'" class="h-8 w-8 text-muted" />
    </div>
    <h3 class="text-sm font-medium text-default">{{ title }}</h3>
    <p v-if="description" class="mt-1 max-w-sm text-xs text-muted">{{ description }}</p>
    <slot />
  </div>
</template>
