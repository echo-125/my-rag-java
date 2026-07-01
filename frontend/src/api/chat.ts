import http from './http'
import type { ChatRequest, ModelConfig } from './types/chat'
import type { QaSaveRequest } from './types/feedback'

/** 获取模型列表 */
export async function getModels(): Promise<ModelConfig[]> {
  const { data } = await http.get<ModelConfig[]>('/api/models')
  return data
}

/** 保存问答记录 */
export async function saveQa(request: QaSaveRequest): Promise<{ id: string }> {
  const { data } = await http.post<{ id: string }>('/api/chat/save', request)
  return data
}

/**
 * 创建 SSE 流式聊天连接
 * 返回 ReadableStream，调用方自行解析 data: 行
 */
export function createChatStream(request: ChatRequest): Promise<Response> {
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
}
