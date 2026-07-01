export interface RagConfigItem {
  key: string
  value: string
  type: 'boolean' | 'number' | 'text' | 'textarea' | 'select'
  description: string
  options?: { label: string; value: string }[]
}

export interface LlmConfig {
  id: string
  provider: string
  modelName: string
  apiKey: string
  baseUrl: string
  temperature: number
  maxTokens: number
  isActive: boolean
  enableToolCalling: boolean
  createdAt: string
}

export interface EmbeddingConfig {
  id: string
  provider: string
  modelName: string
  apiKey: string
  baseUrl: string
  dimension: number
  isActive: boolean
  createdAt: string
}

export interface RerankingConfig {
  id: string
  provider: string
  modelName: string
  apiKey: string
  baseUrl: string
  isActive: boolean
  createdAt: string
}
