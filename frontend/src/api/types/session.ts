/** 会话 */
export interface Session {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

/** 消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}
