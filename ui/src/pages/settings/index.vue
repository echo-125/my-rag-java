<script setup lang="ts">
import { useSettingsStore } from '@/stores/settings'
import { toast } from 'vue-sonner'
import StatusBadge from '@/components/StatusBadge.vue'
import type { RagConfigItem, LlmConfig, EmbeddingConfig, RerankingConfig } from '@/types/settings'

const settingsStore = useSettingsStore()

const activeTab = ref<'rag' | 'llm' | 'embedding' | 'reranking'>('rag')
const showModal = ref(false)
const modalType = ref<'llm' | 'embedding' | 'reranking'>('llm')
const editingItem = ref<Partial<LlmConfig & EmbeddingConfig & RerankingConfig> | null>(null)
const isEditing = ref(false)
const testing = ref(false)

const formData = ref({
  provider: '',
  modelName: '',
  apiKey: '',
  baseUrl: '',
  temperature: 0.7,
  maxTokens: 4096,
  dimension: 1536,
  enableToolCalling: false,
})

function openCreateModal(type: 'llm' | 'embedding' | 'reranking') {
  modalType.value = type
  isEditing.value = false
  editingItem.value = null
  formData.value = {
    provider: '',
    modelName: '',
    apiKey: '',
    baseUrl: '',
    temperature: 0.7,
    maxTokens: 4096,
    dimension: 1536,
    enableToolCalling: false,
  }
  showModal.value = true
}

function openEditModal(type: 'llm' | 'embedding' | 'reranking', item: LlmConfig | EmbeddingConfig | RerankingConfig) {
  modalType.value = type
  isEditing.value = true
  editingItem.value = item
  formData.value = {
    provider: item.provider,
    modelName: item.modelName,
    apiKey: item.apiKey,
    baseUrl: item.baseUrl,
    temperature: 'temperature' in item ? item.temperature : 0.7,
    maxTokens: 'maxTokens' in item ? item.maxTokens : 4096,
    dimension: 'dimension' in item ? item.dimension : 1536,
    enableToolCalling: 'enableToolCalling' in item ? item.enableToolCalling : false,
  }
  showModal.value = true
}

async function handleSave() {
  try {
    if (modalType.value === 'llm') {
      if (isEditing.value && editingItem.value?.id) {
        await settingsStore.updateLlmConfig(editingItem.value.id, formData.value)
        toast.success('LLM 配置已更新')
      } else {
        await settingsStore.createLlmConfig(formData.value)
        toast.success('LLM 配置已创建')
      }
    } else if (modalType.value === 'embedding') {
      if (isEditing.value && editingItem.value?.id) {
        await settingsStore.updateEmbeddingConfig(editingItem.value.id, formData.value)
        toast.success('Embedding 配置已更新')
      } else {
        await settingsStore.createEmbeddingConfig(formData.value)
        toast.success('Embedding 配置已创建')
      }
    } else {
      if (isEditing.value && editingItem.value?.id) {
        await settingsStore.updateRerankingConfig(editingItem.value.id, formData.value)
        toast.success('Reranking 配置已更新')
      } else {
        await settingsStore.createRerankingConfig(formData.value)
        toast.success('Reranking 配置已创建')
      }
    }
    showModal.value = false
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '保存失败')
  }
}

async function handleDelete(type: 'llm' | 'embedding' | 'reranking', id: string) {
  if (type === 'llm') await settingsStore.deleteLlmConfig(id)
  else if (type === 'embedding') await settingsStore.deleteEmbeddingConfig(id)
  else await settingsStore.deleteRerankingConfig(id)
  toast.success('已删除')
}

async function handleActivate(type: 'llm' | 'embedding' | 'reranking', id: string) {
  if (type === 'llm') await settingsStore.activateLlmConfig(id)
  else if (type === 'embedding') await settingsStore.activateEmbeddingConfig(id)
  else await settingsStore.activateRerankingConfig(id)
  toast.success('已激活')
}

async function handleTest(id: string) {
  if (activeTab.value !== 'llm') return
  testing.value = true
  try {
    const result = await settingsStore.testLlmConfig(id)
    if (result.success) toast.success(result.message)
    else toast.error(result.message)
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    testing.value = false
  }
}

async function saveRagConfigs() {
  try {
    await settingsStore.saveRagConfigs(settingsStore.ragConfigs)
    toast.success('RAG 配置已保存')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '保存失败')
  }
}

onMounted(() => {
  settingsStore.fetchRagConfigs()
  settingsStore.fetchLlmConfigs()
  settingsStore.fetchEmbeddingConfigs()
  settingsStore.fetchRerankingConfigs()
})
</script>

<template>
  <div class="flex-1 overflow-hidden flex flex-col">
    <!-- Tab 栏 -->
    <div class="flex items-center gap-1 border-b border-default px-4 py-2">
      <button
        v-for="tab in [
          { key: 'rag', label: 'RAG 策略' },
          { key: 'llm', label: 'LLM 模型' },
          { key: 'embedding', label: 'Embedding' },
          { key: 'reranking', label: 'Reranking' },
        ]"
        :key="tab.key"
        class="px-3 py-1.5 text-xs rounded-lg transition-colors"
        :class="activeTab === tab.key ? 'bg-primary/15 text-primary font-medium' : 'text-muted hover:bg-elevated'"
        @click="activeTab = tab.key as typeof activeTab"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="flex-1 overflow-y-auto p-4">
      <!-- RAG 策略配置 -->
      <div v-if="activeTab === 'rag'" class="max-w-2xl space-y-4">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-sm font-medium">RAG 策略配置</h3>
          <UButton size="xs" color="primary" @click="saveRagConfigs">保存配置</UButton>
        </div>
        <div
          v-for="item in settingsStore.ragConfigs"
          :key="item.key"
          class="rounded-xl border border-default bg-elevated p-4"
        >
          <div class="flex items-center justify-between mb-1">
            <label class="text-sm font-medium text-default">{{ item.key }}</label>
            <span class="text-[10px] text-muted font-mono">{{ item.type }}</span>
          </div>
          <p class="text-xs text-muted mb-3">{{ item.description }}</p>
          <!-- 根据 type 动态渲染 -->
          <UToggle
            v-if="item.type === 'boolean'"
            :model-value="item.value === 'true'"
            @update:model-value="item.value = $event ? 'true' : 'false'"
          />
          <UInput
            v-else-if="item.type === 'number'"
            v-model="item.value"
            type="number"
            class="max-w-xs"
          />
          <UInput
            v-else-if="item.type === 'text'"
            v-model="item.value"
            class="max-w-xs"
          />
          <UTextarea
            v-else-if="item.type === 'textarea'"
            v-model="item.value"
            rows="3"
          />
          <USelect
            v-else-if="item.type === 'select'"
            :items="(item.options ?? []).map(o => ({ label: o.label, value: o.value }))"
            :model-value="item.value"
            @update:model-value="item.value = $event"
          />
        </div>
      </div>

      <!-- 模型配置表格 (LLM / Embedding / Reranking 共用模板) -->
      <div v-else class="space-y-4">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-sm font-medium">
            {{ activeTab === 'llm' ? 'LLM 模型配置' : activeTab === 'embedding' ? 'Embedding 模型配置' : 'Reranking 模型配置' }}
          </h3>
          <UButton size="xs" color="primary" icon="lucide:plus" @click="openCreateModal(activeTab)">
            新增
          </UButton>
        </div>
        <div class="rounded-xl border border-default overflow-hidden">
          <table class="w-full text-xs">
            <thead>
              <tr class="bg-elevated">
                <th class="px-3 py-2 text-left text-muted font-medium">提供商</th>
                <th class="px-3 py-2 text-left text-muted font-medium">模型名</th>
                <th class="px-3 py-2 text-left text-muted font-medium">Base URL</th>
                <th class="px-3 py-2 text-left text-muted font-medium">状态</th>
                <th class="px-3 py-2 text-left text-muted font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in (activeTab === 'llm' ? settingsStore.llmConfigs : activeTab === 'embedding' ? settingsStore.embeddingConfigs : settingsStore.rerankingConfigs)"
                :key="item.id"
                class="border-t border-default hover:bg-elevated/50"
              >
                <td class="px-3 py-2">{{ item.provider }}</td>
                <td class="px-3 py-2 font-mono">{{ item.modelName }}</td>
                <td class="px-3 py-2 font-mono text-muted max-w-[200px] truncate">{{ item.baseUrl }}</td>
                <td class="px-3 py-2">
                  <StatusBadge :active="item.isActive" />
                </td>
                <td class="px-3 py-2">
                  <div class="flex items-center gap-1">
                    <UButton
                      v-if="!item.isActive"
                      icon="lucide:check"
                      size="xs"
                      color="primary"
                      variant="ghost"
                      @click="handleActivate(activeTab, item.id)"
                    />
                    <UButton
                      v-if="activeTab === 'llm'"
                      icon="lucide:play"
                      size="xs"
                      color="info"
                      variant="ghost"
                      :loading="testing"
                      @click="handleTest(item.id)"
                    />
                    <UButton
                      icon="lucide:pencil"
                      size="xs"
                      color="neutral"
                      variant="ghost"
                      @click="openEditModal(activeTab, item)"
                    />
                    <UButton
                      icon="lucide:trash-2"
                      size="xs"
                      color="error"
                      variant="ghost"
                      @click="handleDelete(activeTab, item.id)"
                    />
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div class="text-center py-8 text-xs text-muted">
            暂无配置
          </div>
        </div>
      </div>
    </div>

    <!-- 新增/编辑模态框 -->
    <UModal v-model:open="showModal" :title="isEditing ? '编辑配置' : '新增配置'">
      <template #body>
        <div class="space-y-4 p-2">
          <UFormField label="提供商">
            <UInput v-model="formData.provider" placeholder="openai / ollama / ..." />
          </UFormField>
          <UFormField label="模型名称">
            <UInput v-model="formData.modelName" placeholder="gpt-4o / qwen2.5 / ..." />
          </UFormField>
          <UFormField label="API Key">
            <UInput v-model="formData.apiKey" type="password" placeholder="sk-..." />
          </UFormField>
          <UFormField label="Base URL">
            <UInput v-model="formData.baseUrl" placeholder="https://api.openai.com/v1" class="font-mono text-xs" />
          </UFormField>
          <template v-if="modalType === 'llm'">
            <div class="grid grid-cols-2 gap-4">
              <UFormField label="Temperature">
                <UInput v-model.number="formData.temperature" type="number" />
              </UFormField>
              <UFormField label="Max Tokens">
                <UInput v-model.number="formData.maxTokens" type="number" />
              </UFormField>
            </div>
            <UFormField label="Agent 工具调用">
              <UToggle v-model="formData.enableToolCalling" />
            </UFormField>
          </template>
          <UFormField v-if="modalType === 'embedding'" label="维度">
            <UInput v-model.number="formData.dimension" type="number" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <div class="flex justify-end gap-2">
          <UButton color="neutral" variant="ghost" @click="showModal = false">取消</UButton>
          <UButton color="primary" :disabled="!formData.provider || !formData.modelName" @click="handleSave">
            {{ isEditing ? '更新' : '创建' }}
          </UButton>
        </div>
      </template>
    </UModal>
  </div>
</template>
