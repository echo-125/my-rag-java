import { defineStore } from 'pinia'
import type { RagConfigItem, LlmConfig, EmbeddingConfig, RerankingConfig } from '@/types/settings'
import { api } from '@/utils/api'

export const useSettingsStore = defineStore('settings', () => {
  const ragConfigs = ref<RagConfigItem[]>([])
  const llmConfigs = ref<LlmConfig[]>([])
  const embeddingConfigs = ref<EmbeddingConfig[]>([])
  const rerankingConfigs = ref<RerankingConfig[]>([])

  async function fetchRagConfigs() {
    ragConfigs.value = await api<RagConfigItem[]>('/configs')
  }

  async function saveRagConfigs(items: RagConfigItem[]) {
    await api('/configs', { method: 'PUT', body: items })
    ragConfigs.value = items
  }

  async function fetchLlmConfigs() {
    llmConfigs.value = await api<LlmConfig[]>('/llm-configs')
  }

  async function createLlmConfig(data: Partial<LlmConfig>) {
    const cfg = await api<LlmConfig>('/llm-configs', { method: 'POST', body: data })
    llmConfigs.value.push(cfg)
    return cfg
  }

  async function updateLlmConfig(id: string, data: Partial<LlmConfig>) {
    const cfg = await api<LlmConfig>(`/llm-configs/${id}`, { method: 'PUT', body: data })
    const idx = llmConfigs.value.findIndex(c => c.id === id)
    if (idx >= 0) llmConfigs.value[idx] = cfg
    return cfg
  }

  async function deleteLlmConfig(id: string) {
    await api(`/llm-configs/${id}`, { method: 'DELETE' })
    llmConfigs.value = llmConfigs.value.filter(c => c.id !== id)
  }

  async function activateLlmConfig(id: string) {
    await api(`/llm-configs/${id}/activate`, { method: 'POST' })
    llmConfigs.value.forEach(c => (c.isActive = c.id === id))
  }

  async function testLlmConfig(id: string) {
    return await api<{ success: boolean; message: string }>(`/llm-configs/${id}/test`, { method: 'POST' })
  }

  async function fetchEmbeddingConfigs() {
    embeddingConfigs.value = await api<EmbeddingConfig[]>('/embedding-configs')
  }

  async function createEmbeddingConfig(data: Partial<EmbeddingConfig>) {
    const cfg = await api<EmbeddingConfig>('/embedding-configs', { method: 'POST', body: data })
    embeddingConfigs.value.push(cfg)
    return cfg
  }

  async function updateEmbeddingConfig(id: string, data: Partial<EmbeddingConfig>) {
    const cfg = await api<EmbeddingConfig>(`/embedding-configs/${id}`, { method: 'PUT', body: data })
    const idx = embeddingConfigs.value.findIndex(c => c.id === id)
    if (idx >= 0) embeddingConfigs.value[idx] = cfg
    return cfg
  }

  async function deleteEmbeddingConfig(id: string) {
    await api(`/embedding-configs/${id}`, { method: 'DELETE' })
    embeddingConfigs.value = embeddingConfigs.value.filter(c => c.id !== id)
  }

  async function activateEmbeddingConfig(id: string) {
    await api(`/embedding-configs/${id}/activate`, { method: 'POST' })
    embeddingConfigs.value.forEach(c => (c.isActive = c.id === id))
  }

  async function fetchRerankingConfigs() {
    rerankingConfigs.value = await api<RerankingConfig[]>('/reranking-configs')
  }

  async function createRerankingConfig(data: Partial<RerankingConfig>) {
    const cfg = await api<RerankingConfig>('/reranking-configs', { method: 'POST', body: data })
    rerankingConfigs.value.push(cfg)
    return cfg
  }

  async function updateRerankingConfig(id: string, data: Partial<RerankingConfig>) {
    const cfg = await api<RerankingConfig>(`/reranking-configs/${id}`, { method: 'PUT', body: data })
    const idx = rerankingConfigs.value.findIndex(c => c.id === id)
    if (idx >= 0) rerankingConfigs.value[idx] = cfg
    return cfg
  }

  async function deleteRerankingConfig(id: string) {
    await api(`/reranking-configs/${id}`, { method: 'DELETE' })
    rerankingConfigs.value = rerankingConfigs.value.filter(c => c.id !== id)
  }

  async function activateRerankingConfig(id: string) {
    await api(`/reranking-configs/${id}/activate`, { method: 'POST' })
    rerankingConfigs.value.forEach(c => (c.isActive = c.id === id))
  }

  return {
    ragConfigs, llmConfigs, embeddingConfigs, rerankingConfigs,
    fetchRagConfigs, saveRagConfigs,
    fetchLlmConfigs, createLlmConfig, updateLlmConfig, deleteLlmConfig, activateLlmConfig, testLlmConfig,
    fetchEmbeddingConfigs, createEmbeddingConfig, updateEmbeddingConfig, deleteEmbeddingConfig, activateEmbeddingConfig,
    fetchRerankingConfigs, createRerankingConfig, updateRerankingConfig, deleteRerankingConfig, activateRerankingConfig,
  }
})
