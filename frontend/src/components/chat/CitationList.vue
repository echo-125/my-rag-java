<script setup lang="ts">
import type { Source } from '@/api/types/chat'

defineProps<{ sources: Source[] }>()
</script>

<template>
  <div class="flex flex-wrap gap-2 mt-3 pt-3" style="border-top: 1px dashed var(--border)">
    <div
      v-for="(s, i) in sources"
      :key="i"
      class="inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer transition-colors"
      style="background: var(--bg-elevated); border: 1px solid var(--border); color: var(--text-3)"
      :title="s.path || s.name"
    >
      <span>[{{ i + 1 }}]</span>
      <span class="truncate max-w-[150px]">{{ s.name }}</span>
    </div>
  </div>
</template>
