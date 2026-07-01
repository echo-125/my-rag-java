import http from './http'
import type { RagConfigItem, LlmConfig, EmbeddingConfig, RerankingConfig, TestResult } from './types/settings'

// ─── RAG 配置 ───

export async function getRagConfigs(): Promise<RagConfigItem[]> {
  const { data } = await http.get<RagConfigItem[]>('/api/configs')
  return data
}

export async function updateRagConfigs(updates: Record<string, string>): Promise<{ updated: number }> {
  const { data } = await http.put<{ updated: number }>('/api/configs', updates)
  return data
}

// ─── LLM 配置 ───

export async function getLlmConfigs(): Promise<LlmConfig[]> {
  const { data } = await http.get<LlmConfig[]>('/api/llm-configs')
  return data
}

export async function getLlmConfig(id: string): Promise<LlmConfig> {
  const { data } = await http.get<LlmConfig>(`/api/llm-configs/${id}`)
  return data
}

export async function createLlmConfig(config: Omit<LlmConfig, 'id' | 'isActive'>): Promise<void> {
  await http.post('/api/llm-configs', config)
}

export async function updateLlmConfig(id: string, config: Partial<LlmConfig>): Promise<void> {
  await http.put(`/api/llm-configs/${id}`, config)
}

export async function deleteLlmConfig(id: string): Promise<void> {
  await http.delete(`/api/llm-configs/${id}`)
}

export async function activateLlmConfig(id: string): Promise<void> {
  await http.post(`/api/llm-configs/${id}/activate`)
}

export async function deactivateLlmConfig(id: string): Promise<void> {
  await http.post(`/api/llm-configs/${id}/deactivate`)
}

export async function testLlmConfig(id: string): Promise<TestResult> {
  const { data } = await http.post<TestResult>(`/api/llm-configs/${id}/test`)
  return data
}

// ─── Embedding 配置 ───

export async function getEmbeddingConfigs(): Promise<EmbeddingConfig[]> {
  const { data } = await http.get<EmbeddingConfig[]>('/api/embedding-configs')
  return data
}

export async function getEmbeddingConfig(id: string): Promise<EmbeddingConfig> {
  const { data } = await http.get<EmbeddingConfig>(`/api/embedding-configs/${id}`)
  return data
}

export async function createEmbeddingConfig(config: Omit<EmbeddingConfig, 'id' | 'isActive'>): Promise<void> {
  await http.post('/api/embedding-configs', config)
}

export async function updateEmbeddingConfig(id: string, config: Partial<EmbeddingConfig>): Promise<void> {
  await http.put(`/api/embedding-configs/${id}`, config)
}

export async function deleteEmbeddingConfig(id: string): Promise<void> {
  await http.delete(`/api/embedding-configs/${id}`)
}

export async function activateEmbeddingConfig(id: string): Promise<void> {
  await http.post(`/api/embedding-configs/${id}/activate`)
}

export async function deactivateEmbeddingConfig(id: string): Promise<void> {
  await http.post(`/api/embedding-configs/${id}/deactivate`)
}

export async function testEmbeddingConfig(id: string): Promise<TestResult> {
  const { data } = await http.post<TestResult>(`/api/embedding-configs/${id}/test`)
  return data
}

// ─── Reranking 配置 ───

export async function getRerankingConfigs(): Promise<RerankingConfig[]> {
  const { data } = await http.get<RerankingConfig[]>('/api/reranking-configs')
  return data
}

export async function createRerankingConfig(config: Omit<RerankingConfig, 'id' | 'isActive'>): Promise<void> {
  await http.post('/api/reranking-configs', config)
}

export async function updateRerankingConfig(id: string, config: Partial<RerankingConfig>): Promise<void> {
  await http.put(`/api/reranking-configs/${id}`, config)
}

export async function deleteRerankingConfig(id: string): Promise<void> {
  await http.delete(`/api/reranking-configs/${id}`)
}

export async function activateRerankingConfig(id: string): Promise<void> {
  await http.post(`/api/reranking-configs/${id}/activate`)
}

export async function deactivateRerankingConfig(id: string): Promise<void> {
  await http.post(`/api/reranking-configs/${id}/deactivate`)
}

export async function testRerankingConfig(id: string): Promise<TestResult> {
  const { data } = await http.post<TestResult>(`/api/reranking-configs/${id}/test`)
  return data
}
