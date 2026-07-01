<script setup lang="ts">
import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { NButton, NSwitch, NTag, NSpin, NDataTable } from 'naive-ui'
import type { LlmConfig } from '@/api/types/settings'
import ConfigModal from './ConfigModal.vue'

const store = useSettingsStore()

// ─── Modal 状态 ───
const modalVisible = ref(false)
const editingConfig = ref<LlmConfig | null>(null)

function openAddModal() {
  editingConfig.value = null
  modalVisible.value = true
}

function openEditModal(config: LlmConfig) {
  editingConfig.value = config
  modalVisible.value = true
}

async function handleSave(values: Record<string, string>) {
  const body = {
    name: values.name,
    modelName: values.modelName,
    baseUrl: values.baseUrl,
    apiFormat: values.apiFormat === 'anthropic' ? 'anthropic_messages' as const : 'openai_chat_completions' as const,
    enableToolCalling: false,
  }

  if (editingConfig.value) {
    await store.updateLlmConfig(editingConfig.value.id, body)
  } else {
    await store.createLlmConfig(body)
  }
  modalVisible.value = false
}

async function handleDelete(config: LlmConfig) {
  if (!confirm(`确定删除配置 "${config.name}"？`)) return
  await store.deleteLlmConfig(config.id)
}

async function handleTest(config: LlmConfig) {
  const result = await store.testLlmConfig(config.id)
  if (result.success) {
    window.alert(`${result.message} (${result.responseTimeMs}ms)`)
  } else {
    window.alert('连接失败: ' + result.message)
  }
}

// Modal 字段定义
const modalFields = [
  { key: 'name', label: '配置名称', required: true, placeholder: '如 DeepSeek-Coder' },
  { key: 'modelName', label: '模型名称', required: true, placeholder: '如 deepseek-coder' },
  {
    key: 'apiFormat', label: '类型', type: 'select' as const,
    options: [
      { label: 'OpenAI', value: 'openai_chat_completions' },
      { label: 'Anthropic', value: 'anthropic_messages' },
    ],
  },
  { key: 'baseUrl', label: 'Base URL', required: true, placeholder: 'https://api.deepseek.com/v1' },
  { key: 'apiKey', label: 'API Key', type: 'password' as const, placeholder: 'sk-...' },
]
</script>

<template>
  <div class="rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4 flex items-center justify-between" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: #2563eb"></div>
        LLM (大语言模型) 接入
      </h2>
      <NButton size="small" type="primary" @click="openAddModal">+ 新增模型</NButton>
    </div>

    <div v-if="store.llmConfigs.length === 0" class="py-8 text-center text-sm" style="color: var(--text-4)">
      暂无配置，点击"新增模型"创建
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full text-xs" style="color: var(--text-2)">
        <thead style="background: var(--bg-elevated); color: var(--text-3)">
          <tr style="border-bottom: 1px solid var(--border-subtle)">
            <th class="px-4 py-2 text-left font-medium">名称</th>
            <th class="px-4 py-2 text-left font-medium">基座模型</th>
            <th class="px-4 py-2 text-left font-medium">状态</th>
            <th class="px-4 py-2 text-center font-medium">Agent</th>
            <th class="px-4 py-2 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="config in store.llmConfigs"
            :key="config.id"
            style="border-bottom: 1px solid var(--border-subtle)"
          >
            <td class="px-4 py-3 font-medium" style="color: var(--text-1)">{{ config.name }}</td>
            <td class="px-4 py-3" style="color: var(--text-3)">{{ config.modelName }}</td>
            <td class="px-4 py-3">
              <NTag v-if="config.isActive" type="success" size="small" :bordered="true">已激活</NTag>
              <NTag v-else size="small" :bordered="true">未激活</NTag>
            </td>
            <td class="px-4 py-3 text-center">
              <NSwitch
                :value="config.enableToolCalling"
                @update:value="(v: boolean) => store.toggleToolCalling(config.id, v)"
                size="small"
              />
            </td>
            <td class="px-4 py-2.5 text-right">
              <div class="flex gap-1.5 justify-end flex-wrap">
                <NButton size="tiny" quaternary @click="handleTest(config)">测试</NButton>
                <NButton
                  v-if="config.isActive"
                  size="tiny" quaternary
                  @click="store.deactivateLlmConfig(config.id)"
                >停用</NButton>
                <NButton
                  v-else
                  size="tiny" quaternary type="primary"
                  @click="store.activateLlmConfig(config.id)"
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
      :title="editingConfig ? '编辑 LLM 配置' : '添加 LLM 配置'"
      :fields="modalFields"
      :initial-values="editingConfig ? {
        name: editingConfig.name,
        modelName: editingConfig.modelName,
        apiFormat: editingConfig.apiFormat,
        baseUrl: editingConfig.baseUrl,
        apiKey: '',
      } : undefined"
      @save="handleSave"
    />
  </div>
</template>
