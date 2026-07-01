<script setup lang="ts">
import type { ToolCall } from '@/api/types/chat'
import { TOOL_NAMES, TOOL_ICONS } from '@/utils/constants'

defineProps<{ tools: ToolCall[] }>()
</script>

<template>
  <div class="space-y-1 mt-3 pt-3" style="border-top: 1px solid var(--border-subtle)">
    <div v-for="(t, i) in tools" :key="i" class="flex items-center gap-2 text-xs">
      <span>{{ TOOL_ICONS[t.tool] || '⚙️' }}</span>
      <span style="color: var(--text-2)">{{ TOOL_NAMES[t.tool] || t.tool }}</span>
      <span class="font-mono" style="color: var(--text-3)">{{ t.duration }}ms</span>
    </div>
  </div>
</template>
