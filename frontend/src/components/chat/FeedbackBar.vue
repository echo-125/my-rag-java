<script setup lang="ts">
import { ref } from 'vue'
import { saveQa } from '@/api/chat'
import { submitFeedback } from '@/api/feedback'

const props = defineProps<{
  query: string
  answer: string
}>()

const submitted = ref(false)

async function handleFeedback(rating: 1 | -1) {
  try {
    const { id } = await saveQa({ question: props.query, answer: props.answer.substring(0, 500), modelName: '' })
    await submitFeedback({ qaHistoryId: id, rating })
    submitted.value = true
  } catch {}
}
</script>

<template>
  <div v-if="!submitted" class="flex items-center gap-2 mt-2 pt-2" style="border-top: 1px solid var(--border-subtle)">
    <span class="text-[11px]" style="color: var(--text-4)">有帮助？</span>
    <button @click="handleFeedback(1)" class="px-1.5 py-0.5 text-xs rounded transition-colors" style="color: var(--text-4)">👍</button>
    <button @click="handleFeedback(-1)" class="px-1.5 py-0.5 text-xs rounded transition-colors" style="color: var(--text-4)">👎</button>
  </div>
  <div v-else class="mt-2 pt-2 text-[11px]" style="border-top: 1px solid var(--border-subtle); color: var(--text-4)">
    感谢反馈
  </div>
</template>
