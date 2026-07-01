<script setup lang="ts">
import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { NButton, NTag } from 'naive-ui'
import type { EmbeddingConfig } from '@/api/types/settings'
import ConfigModal from './ConfigModal.vue'

const store = useSettingsStore()

const modalVisible = ref(false)
const editingConfig = ref<EmbeddingConfig | null>(null)

function openAddModal() {
  editingConfig.value = null
  modalVisible.value = true
}

function openEditModal(config: EmbeddingConfig) {
  editingConfig.value = config
  modalVisible.value = true
}

async function handleSave(values: Record<string, string>) {
  const body = {
    name: values.name,
    provider: values.provider as 'ollama' | 'openai',
    modelName: values.modelName,
    baseUrl: values.baseUrl,
    dimension: parseInt(values.dimension) || 2560,
  }

  if (editingConfig.value) {
    await store.updateEmbeddingConfig(editingConfig.value.id, body)
  } else {
    await store.createEmbeddingConfig(body)
  }
  modalVisible.value = false
}

async function handleDelete(config: EmbeddingConfig) {
  if (!confirm(`确定删除 Embedding 配置 "${config.name}"？`)) return
  await store.deleteEmbeddingConfig(config.id)
}

async function handleTest(config: EmbeddingConfig) {
  const result = await store.testEmbeddingConfig(config.id)
  if (result.success) {
    window.alert(`${result.message} (${result.responseTimeMs}ms)`)
  } else {
    window.alert('连接失败: ' + result.message)
  }
}

const modalFields = [
  { key: 'name', label: '配置名称', required: true, placeholder: '如 本地 Ollama qwen3-embedding' },
  {
    key: 'provider', label: 'Provider', type: 'select' as const,
    options: [
      { label: 'Ollama', value: 'ollama' },
      { label: 'OpenAI', value: 'openai' },
    ],
  },
  { key: 'modelName', label: '模型名称', required: true, placeholder: 'qwen3-embedding:4b_Q6' },
  { key: 'baseUrl', label: 'Base URL', required: true, placeholder: 'http://localhost:11434' },
  { key: 'apiKey', label: 'API Key', type: 'password' as const, placeholder: '留空则使用 Ollama 本地' },
  { key: 'dimension', label: '向量维度', required: true, placeholder: '2560', type: 'number' as const },
]
</script>

<template>
  <div class="rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4 flex items-center justify-between" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: #9333ea"></div>
        Embedding (向量模型) 接入
      </h2>
      <NButton size="small" type="primary" @click="openAddModal">+ 新增向量</NButton>
    </div>

    <div v-if="store.embeddingConfigs.length === 0" class="py-8 text-center text-sm" style="color: var(--text-4)">
      暂无配置，点击"新增向量"创建
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-xs" style="color: var(--text-2)">
        <thead style="background: var(--bg-elevated); color: var(--text-3)">
          <tr style="border-bottom: 1px solid var(--border-subtle)">
            <th class="px-4 py-2 text-left font-medium">名称</th>
            <th class="px-4 py-2 text-left font-medium">维度</th>
            <th class="px-4 py-2 text-left font-medium">状态</th>
            <th class="px-4 py-2 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="config in store.embeddingConfigs"
            :key="config.id"
            style="border-bottom: 1px solid var(--border-subtle)"
          >
            <td class="px-4 py-3 font-medium" style="color: var(--text-1)">{{ config.name }}</td>
            <td class="px-4 py-3">{{ config.dimension }}</td>
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
                  @click="store.deactivateEmbeddingConfig(config.id)"
                >停用</NButton>
                <NButton
                  v-else
                  size="tiny" quaternary type="primary"
                  @click="store.activateEmbeddingConfig(config.id)"
                >激活</NButton>
                <NButton size="tiny" quaternary @click="openEditModal(config)">编辑</NButton>
                <NButton size="tiny" quaternary type="error" @click="handleDelete(config)">删除</NButton>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <ConfigModal
      v-model:visible="modalVisible"
      :title="editingConfig ? '编辑 Embedding 配置' : '添加 Embedding 配置'"
      :fields="modalFields"
      :initial-values="editingConfig ? {
        name: editingConfig.name,
        provider: editingConfig.provider,
        modelName: editingConfig.modelName,
        baseUrl: editingConfig.baseUrl,
        apiKey: '',
        dimension: String(editingConfig.dimension),
      } : { dimension: '2560' }"
      @save="handleSave"
    />
  </div>
</template>
