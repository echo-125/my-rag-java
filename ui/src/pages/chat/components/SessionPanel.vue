<script setup lang="ts">
import { useChatStore } from '@/stores/chat'
import { formatRelative } from '@/utils/format'

const chatStore = useChatStore()

defineEmits<{
  newChat: []
}>()

function handleSelect(id: string) {
  chatStore.setActiveSession(id)
}
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between px-3 py-2 border-b border-default">
      <span class="text-xs font-medium text-muted uppercase tracking-wider">会话</span>
      <UButton
        icon="lucide:plus"
        size="xs"
        color="primary"
        variant="ghost"
        @click="$emit('newChat')"
      />
    </div>
    <div class="flex-1 overflow-y-auto py-1">
      <div v-if="chatStore.loadingSessions" class="flex justify-center py-8">
        <UIcon name="lucide:loader-2" class="h-5 w-5 animate-spin text-muted" />
      </div>
      <div v-else-if="chatStore.sessions.length === 0" class="px-3 py-8 text-center text-xs text-muted">
        暂无会话记录
      </div>
      <button
        v-for="session in chatStore.sessions"
        :key="session.id"
        class="flex w-full flex-col gap-0.5 px-3 py-2 text-left text-sm transition-colors rounded-lg mx-1"
        :class="chatStore.activeSessionId === session.id
          ? 'bg-primary/15 text-primary'
          : 'text-default hover:bg-elevated'"
        @click="handleSelect(session.id)"
      >
        <span class="truncate text-xs font-medium">{{ session.title || '新会话' }}</span>
        <span class="text-[10px] text-muted font-mono">{{ formatRelative(session.updatedAt) }}</span>
      </button>
    </div>
  </div>
</template>
