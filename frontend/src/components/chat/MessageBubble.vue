<script setup lang="ts">
defineProps<{
  content: string
  role: 'user' | 'assistant'
}>()
</script>

<template>
  <div :class="role === 'user' ? 'flex justify-end' : 'flex justify-start'" class="mb-3 animate-slide-in">
    <div
      class="rounded-2xl px-5 py-3 max-w-[80%] shadow-sm"
      :style="{
        background: role === 'user' ? '#f3f4f6' : 'transparent',
        color: role === 'user' ? 'var(--text-1)' : 'var(--text-2)',
      }"
    >
      <div class="text-sm leading-relaxed whitespace-pre-wrap">{{ content }}</div>
    </div>
  </div>
</template>
