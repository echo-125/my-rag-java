<script setup lang="ts">
defineEmits<{
  quickAction: [query: string]
}>()

const quickActions = [
  { label: '系统架构概览', query: '请介绍一下这个系统的整体架构', icon: 'lucide:network' },
  { label: '代码质量分析', query: '分析项目中潜在的代码质量问题', icon: 'lucide:search-code' },
  { label: 'API 文档生成', query: '为项目中的 API 端点生成文档', icon: 'lucide:file-code' },
  { label: '数据模型梳理', query: '梳理项目中所有的数据模型及其关系', icon: 'lucide:database' },
]
</script>

<template>
  <div class="flex flex-col items-center justify-center h-full px-6">
    <div class="mb-6 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/15">
      <UIcon name="lucide:message-square" class="h-8 w-8 text-primary" />
    </div>
    <h2 class="text-lg font-semibold text-default mb-1">RAG 对话实验台</h2>
    <p class="text-sm text-muted mb-8">基于知识库的智能问答，支持流式对话与工具调用</p>
    <div class="grid grid-cols-2 gap-3 w-full max-w-md">
      <button
        v-for="action in quickActions"
        :key="action.label"
        class="flex items-center gap-2 rounded-xl border border-default bg-elevated p-3 text-left text-sm transition-all hover:border-primary/40 hover:bg-primary/5"
        @click="$emit('quickAction', action.query)"
      >
        <UIcon :name="action.icon" class="h-4 w-4 text-primary shrink-0" />
        <span>{{ action.label }}</span>
      </button>
    </div>
  </div>
</template>
