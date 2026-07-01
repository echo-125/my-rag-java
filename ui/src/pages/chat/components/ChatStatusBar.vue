<script setup lang="ts">
import StatusBadge from '@/components/StatusBadge.vue'
import { truncateId } from '@/utils/format'

defineProps<{
  modelName?: string
  sessionId?: string
  chunksCount?: number
  projectsCount?: number
  ragEnabled?: {
    bm25: boolean
    reranking: boolean
    queryRewrite: boolean
  }
}>()
</script>

<template>
  <div class="flex items-center gap-3 border-b border-default bg-elevated/50 px-4 py-1.5 text-[11px] font-mono text-muted">
    <span class="flex items-center gap-1">
      <UIcon name="lucide:brain" class="h-3 w-3 text-primary" />
      {{ modelName ?? '未配置' }}
    </span>
    <span v-if="sessionId" class="text-muted/60">|</span>
    <span v-if="sessionId" class="flex items-center gap-1">
      <UIcon name="lucide:hash" class="h-3 w-3" />
      {{ truncateId(sessionId) }}
    </span>
    <span class="text-muted/60">|</span>
    <span>{{ chunksCount ?? 0 }} chunks</span>
    <span class="text-muted/60">|</span>
    <span>{{ projectsCount ?? 0 }} projects</span>
    <span class="text-muted/60">|</span>
    <span class="flex items-center gap-1.5">
      <StatusBadge :active="ragEnabled?.bm25 ?? false" active-label="BM25" inactive-label="BM25" />
      <StatusBadge :active="ragEnabled?.reranking ?? false" active-label="RERANK" inactive-label="RERANK" />
      <StatusBadge :active="ragEnabled?.queryRewrite ?? false" active-label="QR" inactive-label="QR" />
    </span>
  </div>
</template>
