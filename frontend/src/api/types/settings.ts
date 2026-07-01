/** RAG 配置项（/api/configs） */
export interface RagConfigItem {
  key: string
  value: string
  type: 'boolean' | 'number' | 'text' | 'textarea' | 'select'
  description?: string
}

/** LLM 配置（/api/llm-configs） */
export interface LlmConfig {
  id: string
  name: string
  modelName: string
  baseUrl: string
  apiFormat: 'openai_chat_completions' | 'anthropic_messages'
  isActive: boolean
  enableToolCalling: boolean
}

/** Embedding 配置（/api/embedding-configs） */
export interface EmbeddingConfig {
  id: string
  name: string
  provider: 'ollama' | 'openai'
  modelName: string
  baseUrl: string
  dimension: number
  isActive: boolean
}

/** Reranking 配置（/api/reranking-configs） */
export interface RerankingConfig {
  id: string
  name: string
  provider: 'ollama' | 'api'
  modelName: string
  baseUrl: string
  isActive: boolean
}

/** 模型测试结果 */
export interface TestResult {
  success: boolean
  message: string
  responseTimeMs?: number
}
