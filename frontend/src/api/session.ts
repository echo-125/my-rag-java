import http from './http'
import type { Session, Message } from './types/session'

/** 获取会话列表 */
export async function getSessions(): Promise<Session[]> {
  const { data } = await http.get<Session[]>('/api/sessions')
  return data
}

/** 获取会话消息 */
export async function getSessionMessages(sessionId: string): Promise<Message[]> {
  const { data } = await http.get<Message[]>(`/api/sessions/${sessionId}/messages`)
  return data
}

/** 删除会话 */
export async function deleteSession(sessionId: string): Promise<void> {
  await http.delete(`/api/sessions/${sessionId}`)
}
