<script setup lang="ts">
import { useAutoResizeTextarea } from '@/composables/useAutoResizeTextarea'

const props = defineProps<{
  disabled?: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  send: [query: string]
  stop: []
}>()

const input = ref('')
const { autoResize } = useAutoResizeTextarea()

function handleSubmit() {
  if (!input.value.trim() || props.disabled) return
  emit('send', input.value.trim())
  input.value = ''
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSubmit()
  }
}
</script>

<template>
  <div class="border-t border-default bg-default p-3">
    <div class="flex items-end gap-2 rounded-xl border border-default bg-elevated px-3 py-2 transition-colors focus-within:border-primary/50">
      <span class="select-none text-primary font-mono text-sm pb-0.5">&gt;</span>
      <textarea
        v-model="input"
        placeholder="输入问题... (Enter 发送, Shift+Enter 换行)"
        class="flex-1 resize-none bg-transparent text-sm text-default outline-none placeholder:text-muted min-h-[24px] max-h-[120px] py-0.5"
        rows="1"
        :disabled="disabled"
        @keydown="handleKeydown"
        @input="autoResize"
      />
      <UButton
        v-if="loading"
        icon="lucide:octagon"
        size="xs"
        color="error"
        variant="ghost"
        @click="emit('stop')"
      />
      <UButton
        v-else
        icon="lucide:arrow-up"
        size="xs"
        color="primary"
        variant="solid"
        :disabled="!input.trim() || disabled"
        @click="handleSubmit"
      />
    </div>
  </div>
</template>
