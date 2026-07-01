<script setup lang="ts">
import { onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import RagConfig from '@/components/settings/RagConfig.vue'
import LlmTable from '@/components/settings/LlmTable.vue'
import EmbedTable from '@/components/settings/EmbedTable.vue'
import RerankTable from '@/components/settings/RerankTable.vue'

const store = useSettingsStore()

onMounted(() => {
  store.loadAll()
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-6" style="background: var(--bg-page)">
    <div class="max-w-[1600px] mx-auto">
      <div class="mb-6">
        <h1 class="text-xl font-bold" style="color: var(--text-1)">系统设置</h1>
        <p class="text-sm mt-1" style="color: var(--text-3)">管理 RAG 策略参数、大语言模型及向量检索配置</p>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
        <!-- 左侧：RAG 配置 -->
        <RagConfig />

        <!-- 右侧：模型配置 -->
        <div class="flex flex-col gap-6">
          <LlmTable />
          <EmbedTable />
          <RerankTable />
        </div>
      </div>
    </div>
  </div>
</template>
