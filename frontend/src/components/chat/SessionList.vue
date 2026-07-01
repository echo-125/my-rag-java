<script setup lang="ts">
import { useChatStore } from '@/stores/chat'
import { formatTime } from '@/utils/format'

const store = useChatStore()

function isActive(id: string) {
  return store.currentSessionId === id
}
</script>

<template>
  <div class="flex-1 overflow-y-auto p-2 space-y-0.5">
    <div v-if="store.sessions.length === 0" class="text-xs text-center py-4" style="color: var(--text-4)">暂无实验记录</div>
    <div
      v-for="session in store.sessions"
      :key="session.id"
      class="px-2.5 py-2 rounded-md cursor-pointer transition-colors"
      :style="{
        background: isActive(session.id) ? '#ecfdf5' : 'transparent',
        borderLeft: isActive(session.id) ? '2px solid #059669' : '2px solid transparent',
      }"
      @click="store.switchSession(session.id)"
      :title="session.title"
    >
      <div class="text-xs font-medium truncate" :style="{ color: isActive(session.id) ? '#065f46' : 'var(--text-2)' }">
        {{ session.title }}
      </div>
      <div class="flex items-center justify-between mt-1">
        <span class="text-[10px] font-mono" :style="{ color: isActive(session.id) ? '#047857' : 'var(--text-4)' }">
          {{ formatTime(session.updatedAt) }}
        </span>
        <button
          class="text-[10px] rounded px-0.5 opacity-0 transition-opacity hover:text-red-500"
          style="color: var(--border)"
          @click.stop="store.deleteSession(session.id)"
          title="删除"
        >✕</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
div:hover > button { opacity: 1 !important; }
</style>
