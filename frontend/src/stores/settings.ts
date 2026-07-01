import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RagConfigItem, LlmConfig, EmbeddingConfig, RerankingConfig } from '@/api/types/settings'
import * as settingsApi from '@/api/settings'

export const useSettingsStore = defineStore('settings', () => {
  const ragConfigs = ref<RagConfigItem[]>([])
  const llmConfigs = ref<LlmConfig[]>([])
  const embeddingConfigs = ref<EmbeddingConfig[]>([])
  const rerankingConfigs = ref<RerankingConfig[]>([])
  const loading = ref(false)

  // ─── RAG 配置 ───

  async function loadRagConfigs() {
    loading.value = true
    try {
      ragConfigs.value = await settingsApi.getRagConfigs()
    } finally {
      loading.value = false
    }
  }

  async function saveRagConfigs(updates: Record<string, string>) {
    const result = await settingsApi.updateRagConfigs(updates)
    await loadRagConfigs()
    return result
  }

  // ─── LLM 配置 ───

  async function loadLlmConfigs() {
    llmConfigs.value = await settingsApi.getLlmConfigs()
  }

  async function createLlmConfig(config: Omit<LlmConfig, 'id' | 'isActive'>) {
    await settingsApi.createLlmConfig(config)
    await loadLlmConfigs()
  }

  async function updateLlmConfig(id: string, config: Partial<LlmConfig>) {
    await settingsApi.updateLlmConfig(id, config)
    await loadLlmConfigs()
  }

  async function deleteLlmConfig(id: string) {
    await settingsApi.deleteLlmConfig(id)
    await loadLlmConfigs()
  }

  async function activateLlmConfig(id: string) {
    await settingsApi.activateLlmConfig(id)
    await loadLlmConfigs()
  }

  async function deactivateLlmConfig(id: string) {
    await settingsApi.deactivateLlmConfig(id)
    await loadLlmConfigs()
  }

  async function testLlmConfig(id: string) {
    return await settingsApi.testLlmConfig(id)
  }

  async function toggleToolCalling(id: string, value: boolean) {
    await settingsApi.updateLlmConfig(id, { enableToolCalling: value })
    await loadLlmConfigs()
  }

  // ─── Embedding 配置 ───

  async function loadEmbeddingConfigs() {
    embeddingConfigs.value = await settingsApi.getEmbeddingConfigs()
  }

  async function createEmbeddingConfig(config: Omit<EmbeddingConfig, 'id' | 'isActive'>) {
    await settingsApi.createEmbeddingConfig(config)
    await loadEmbeddingConfigs()
  }

  async function updateEmbeddingConfig(id: string, config: Partial<EmbeddingConfig>) {
    await settingsApi.updateEmbeddingConfig(id, config)
    await loadEmbeddingConfigs()
  }

  async function deleteEmbeddingConfig(id: string) {
    await settingsApi.deleteEmbeddingConfig(id)
    await loadEmbeddingConfigs()
  }

  async function activateEmbeddingConfig(id: string) {
    await settingsApi.activateEmbeddingConfig(id)
    await loadEmbeddingConfigs()
  }

  async function deactivateEmbeddingConfig(id: string) {
    await settingsApi.deactivateEmbeddingConfig(id)
    await loadEmbeddingConfigs()
  }

  async function testEmbeddingConfig(id: string) {
    return await settingsApi.testEmbeddingConfig(id)
  }

  // ─── Reranking 配置 ───

  async function loadRerankingConfigs() {
    rerankingConfigs.value = await settingsApi.getRerankingConfigs()
  }

  async function createRerankingConfig(config: Omit<RerankingConfig, 'id' | 'isActive'>) {
    await settingsApi.createRerankingConfig(config)
    await loadRerankingConfigs()
  }

  async function updateRerankingConfig(id: string, config: Partial<RerankingConfig>) {
    await settingsApi.updateRerankingConfig(id, config)
    await loadRerankingConfigs()
  }

  async function deleteRerankingConfig(id: string) {
    await settingsApi.deleteRerankingConfig(id)
    await loadRerankingConfigs()
  }

  async function activateRerankingConfig(id: string) {
    await settingsApi.activateRerankingConfig(id)
    await loadRerankingConfigs()
  }

  async function deactivateRerankingConfig(id: string) {
    await settingsApi.deactivateRerankingConfig(id)
    await loadRerankingConfigs()
  }

  async function testRerankingConfig(id: string) {
    return await settingsApi.testRerankingConfig(id)
  }

  // ─── 加载全部 ───

  async function loadAll() {
    loading.value = true
    try {
      await Promise.all([
        loadRagConfigs(),
        loadLlmConfigs(),
        loadEmbeddingConfigs(),
        loadRerankingConfigs(),
      ])
    } finally {
      loading.value = false
    }
  }

  return {
    ragConfigs, llmConfigs, embeddingConfigs, rerankingConfigs, loading,
    loadRagConfigs, saveRagConfigs,
    loadLlmConfigs, createLlmConfig, updateLlmConfig, deleteLlmConfig,
    activateLlmConfig, deactivateLlmConfig, testLlmConfig, toggleToolCalling,
    loadEmbeddingConfigs, createEmbeddingConfig, updateEmbeddingConfig, deleteEmbeddingConfig,
    activateEmbeddingConfig, deactivateEmbeddingConfig, testEmbeddingConfig,
    loadRerankingConfigs, createRerankingConfig, updateRerankingConfig, deleteRerankingConfig,
    activateRerankingConfig, deactivateRerankingConfig, testRerankingConfig,
    loadAll,
  }
})
