<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()

const emit = defineEmits<{
  quickQuestion: [text: string]
}>()

const stats = computed(() => {
  // 从 store 中获取状态
  return {
    llm: store.models.find(m => m.isActive)?.name || '未配置',
  }
})
</script>

<template>
  <div class="max-w-[640px] mx-auto py-10 px-6 animate-fade-in">
    <div class="flex items-center gap-4 mb-6">
      <div class="w-10 h-10 rounded-lg flex items-center justify-center text-xl" style="background: #f0fdf4; border: 1px solid #bbf7d0">⚡</div>
      <div>
        <h2 class="text-lg font-semibold" style="color: var(--text-1)">RAG 实验台就绪</h2>
        <p class="text-xs" style="color: var(--text-3)">本地知识增强问答工作台 · 检索增强生成</p>
      </div>
    </div>

    <div class="grid grid-cols-4 gap-3 mb-6">
      <div class="text-center p-3 rounded-lg" style="background: var(--bg-elevated); border: 1px solid var(--border)">
        <span class="block text-[10px] uppercase tracking-wider mb-1" style="color: var(--text-4)">模型</span>
        <span class="block text-xs font-semibold font-mono" style="color: var(--text-2)">{{ stats.llm }}</span>
      </div>
      <div class="text-center p-3 rounded-lg" style="background: var(--bg-elevated); border: 1px solid var(--border)">
        <span class="block text-[10px] uppercase tracking-wider mb-1" style="color: var(--text-4)">状态</span>
        <span class="block text-xs font-semibold font-mono" style="color: var(--text-2)">就绪</span>
      </div>
    </div>

    <div class="grid grid-cols-2 gap-2.5">
      <button class="flex items-center gap-2.5 p-3 rounded-lg text-left transition-colors" style="border: 1px solid var(--border); color: var(--text-2)" @click="emit('quickQuestion', '知识库中有哪些项目？')">
        <span>📊</span><span class="text-xs font-medium">查看知识库状态</span>
      </button>
      <button class="flex items-center gap-2.5 p-3 rounded-lg text-left transition-colors" style="border: 1px solid var(--border); color: var(--text-2)" @click="emit('quickQuestion', '总结一下已入库的主要内容')">
        <span>📋</span><span class="text-xs font-medium">总结已入库内容</span>
      </button>
    </div>
  </div>
</template>
