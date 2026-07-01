<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts } = useToast()

const typeColors: Record<string, string> = {
  success: '#059669',
  error: '#dc2626',
  info: '#2563eb',
  warning: '#d97706',
}
</script>

<template>
  <div class="fixed bottom-[15%] left-1/2 -translate-x-1/2 z-[9999] flex flex-col gap-2 max-w-[560px] w-[90%] pointer-events-none">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="px-6 py-3.5 rounded-xl text-white text-[15px] font-medium leading-relaxed flex items-center gap-2.5 pointer-events-auto shadow-lg"
        :style="{ background: typeColors[toast.type] }"
      >
        {{ toast.message }}
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-enter-active {
  transition: all 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
