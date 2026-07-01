/** 聊天请求 */
export interface ChatRequest {
  query: string
  modelKey: string
  sessionId: string | null
}

/** 聊天流式事件 — 来自 /api/chat/stream 的 data: 行 */
export type ChatStreamEvent =
  | ChatStreamTokenEvent
  | ChatStreamErrorEvent

/** 文本 token 事件（流式过程中持续发送） */
export interface ChatStreamTokenEvent {
  text: string
  /** 首个事件中携带引用来源（可选） */
  sources?: Source[]
  /** 首个事件或结束事件中携带工具调用记录（可选） */
  toolMetadata?: ToolCall[]
}

/** 错误事件 */
export interface ChatStreamErrorEvent {
  error: string
}

/** 引用来源 */
export interface Source {
  id: number
  name: string
  path: string
}

/**
 * 工具调用记录
 * 对照后端 AgentToolMetadata.ToolCallRecord:
 *   record ToolCallRecord(String toolName, String args, long durationMs)
 * 序列化为 JSON（RagChatService:534-536）:
 *   {"tool":"...","args":"...","duration":...}
 */
export interface ToolCall {
  /** 工具名称 */
  tool: 'searchKnowledge' | 'readFile' | 'listDirectory' | 'getKnowledgeBaseStats' | string
  /** 工具调用参数（原始参数字符串） */
  args: string
  /** 执行耗时（毫秒） */
  duration: number
}

/** 模型配置（来自 /api/models） */
export interface ModelConfig {
  id: string
  name: string
  isActive: boolean
}
