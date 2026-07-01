<script setup lang="ts">
import { NButton, NTag } from 'naive-ui'
import type { Project } from '@/api/types/ingestion'

defineProps<{
  projects: Project[]
  chunkCounts: Record<string, number>
}>()

const emit = defineEmits<{
  view: [project: Project]
  reIngest: [project: Project]
  delete: [project: Project]
}>()
</script>

<template>
  <div class="space-y-3">
    <div
      v-for="project in projects"
      :key="project.id"
      class="flex flex-col p-3 rounded-lg shadow-sm"
      style="background: var(--bg-card); border: 1px solid var(--border)"
    >
      <div class="flex justify-between items-start">
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <div class="text-sm font-semibold truncate" style="color: var(--text-1)">{{ project.name }}</div>
            <NTag v-if="project.status === 'completed'" type="success" size="tiny" :bordered="true">已入库</NTag>
            <NTag v-else size="tiny" :bordered="true">待入库</NTag>
          </div>
          <div class="text-xs mt-1 break-all font-mono" style="color: var(--text-3)">{{ project.path }}</div>
          <div class="text-xs mt-1" style="color: var(--text-4)">{{ chunkCounts[project.name] || 0 }} chunks</div>
        </div>
      </div>
      <div class="flex gap-2 mt-2 pt-2" style="border-top: 1px solid var(--border-subtle)">
        <NButton size="tiny" quaternary class="flex-1" @click="emit('view', project)">详情</NButton>
        <NButton
          v-if="project.status === 'completed'"
          size="tiny"
          type="primary"
          class="flex-1"
          @click="emit('reIngest', project)"
        >重新入库</NButton>
        <NButton size="tiny" quaternary type="error" @click="emit('delete', project)">删除</NButton>
      </div>
    </div>
  </div>
</template>
