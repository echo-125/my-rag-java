<script setup lang="ts">
import type { StreamMessage } from '@/composables/useChatStream'

defineProps<{ message: StreamMessage }>()
</script>

<template>
  <div class="flex justify-end gap-3 px-4 py-2">
    <div class="max-w-[75%] rounded-xl bg-primary/15 px-4 py-2.5 text-sm">
      {{ message.content }}
    </div>
    <div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-elevated text-xs font-bold text-primary">
      U
    </div>
  </div>
</template>
