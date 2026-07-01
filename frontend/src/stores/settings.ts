import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RagConfigItem, LlmConfig, EmbeddingConfig, RerankingConfig } from '@/api/types/settings'

export const useSettingsStore = defineStore('settings', () => {
  const ragConfigs = ref<RagConfigItem[]>([])
  const llmConfigs = ref<LlmConfig[]>([])
  const embeddingConfigs = ref<EmbeddingConfig[]>([])
  const rerankingConfigs = ref<RerankingConfig[]>([])
  const loading = ref(false)

  return { ragConfigs, llmConfigs, embeddingConfigs, rerankingConfigs, loading }
})
