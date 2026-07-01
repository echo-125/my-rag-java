<script setup lang="ts">
import { ref, computed } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { NButton, NTag } from 'naive-ui'
import type { RerankingConfig } from '@/api/types/settings'
import ConfigModal from './ConfigModal.vue'

const store = useSettingsStore()

const modalVisible = ref(false)
const editingConfig = ref<RerankingConfig | null>(null)

const hasActiveRerank = computed(() => store.rerankingConfigs.some(c => c.isActive))

function openAddModal() {
  editingConfig.value = null
  modalVisible.value = true
}

function openEditModal(config: RerankingConfig) {
  editingConfig.value = config
  modalVisible.value = true
}

async function handleSave(values: Record<string, string>) {
  const body = {
    name: values.name,
    provider: values.provider as 'ollama' | 'api',
    modelName: values.modelName,
    baseUrl: values.baseUrl,
  }

  if (editingConfig.value) {
    await store.updateRerankingConfig(editingConfig.value.id, body)
  } else {
    await store.createRerankingConfig(body)
  }
  modalVisible.value = false
}

async function handleDelete(config: RerankingConfig) {
  if (!confirm(`确定删除 Reranking 模型 "${config.name}"？`)) return
  await store.deleteRerankingConfig(config.id)
}

async function handleTest(config: RerankingConfig) {
  const result = await store.testRerankingConfig(config.id)
  if (result.success) {
    window.alert(`${result.message} (${result.responseTimeMs}ms)`)
  } else {
    window.alert('连接失败: ' + result.message)
  }
}

const modalFields = [
  { key: 'name', label: '配置名称', required: true, placeholder: '如 BGE-Reranker-v2-m3' },
  {
    key: 'provider', label: '接入方式', type: 'select' as const,
    options: [
      { label: 'Ollama（本地）', value: 'ollama' },
      { label: 'API（远程服务）', value: 'api' },
    ],
  },
  { key: 'modelName', label: '模型名称', required: true, placeholder: 'bge-reranker-v2-m3' },
  { key: 'baseUrl', label: 'Base URL', required: true, placeholder: 'http://localhost:11434' },
  { key: 'apiKey', label: 'API Key', type: 'password' as const, placeholder: 'sk-...' },
]
</script>

<template>
  <div class="rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4 flex items-center justify-between" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: #d97706"></div>
        Reranking (重排模型) 接入
      </h2>
      <NButton size="small" type="primary" @click="openAddModal">+ 新增模型</NButton>
    </div>

    <div v-if="store.rerankingConfigs.length === 0" class="py-8 text-center text-sm" style="color: var(--text-4)">
      暂无配置，点击"新增模型"创建
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-xs" style="color: var(--text-2)">
        <thead style="background: var(--bg-elevated); color: var(--text-3)">
          <tr style="border-bottom: 1px solid var(--border-subtle)">
            <th class="px-4 py-2 text-left font-medium">名称</th>
            <th class="px-4 py-2 text-left font-medium">类型</th>
            <th class="px-4 py-2 text-left font-medium">模型</th>
            <th class="px-4 py-2 text-left font-medium">状态</th>
            <th class="px-4 py-2 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="config in store.rerankingConfigs"
            :key="config.id"
            style="border-bottom: 1px solid var(--border-subtle)"
          >
            <td class="px-4 py-3 font-medium" style="color: var(--text-1)">{{ config.name }}</td>
            <td class="px-4 py-3">
              <NTag v-if="config.provider === 'api'" type="info" size="small" :bordered="true">API</NTag>
              <NTag v-else type="success" size="small" :bordered="true">Ollama</NTag>
            </td>
            <td class="px-4 py-3">{{ config.modelName }}</td>
            <td class="px-4 py-3">
              <NTag v-if="config.isActive" type="success" size="small" :bordered="true">已激活</NTag>
              <NTag v-else size="small" :bordered="true">未激活</NTag>
            </td>
            <td class="px-4 py-2.5 text-right">
              <div class="flex gap-1.5 justify-end flex-wrap">
                <NButton size="tiny" quaternary @click="handleTest(config)">测试</NButton>
                <NButton
                  v-if="config.isActive"
                  size="tiny" quaternary
                  @click="store.deactivateRerankingConfig(config.id)"
                >停用</NButton>
                <NButton
                  v-else
                  size="tiny" quaternary type="primary"
                  @click="store.activateRerankingConfig(config.id)"
                >激活</NButton>
                <NButton size="tiny" quaternary @click="openEditModal(config)">编辑</NButton>
                <NButton size="tiny" quaternary type="error" @click="handleDelete(config)">删除</NButton>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="!hasActiveRerank && store.rerankingConfigs.length > 0" class="px-4 py-3 text-xs" style="background: #fffbeb; border-top: 1px solid #fef3c7; color: #92400e">
      未配置重排模型，Reranking 功能已自动禁用。请添加模型后开启。
    </div>

    <ConfigModal
      v-model:visible="modalVisible"
      :title="editingConfig ? '编辑 Reranking 模型' : '添加 Reranking 模型'"
      :fields="modalFields"
      :initial-values="editingConfig ? {
        name: editingConfig.name,
        provider: editingConfig.provider,
        modelName: editingConfig.modelName,
        baseUrl: editingConfig.baseUrl,
        apiKey: '',
      } : { baseUrl: 'http://localhost:11434' }"
      @save="handleSave"
    />
  </div>
</template>
