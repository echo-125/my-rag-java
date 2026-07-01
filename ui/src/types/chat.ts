export interface ChatSession {
  id: string
  title: string
  modelKey: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

export interface ChatMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant'
  content: string
  sources: Source[]
  toolMetadata: ToolCall[]
  createdAt: string
}

export interface Source {
  fileName: string
  projectName: string
  chunkIndex: number
  score: number
}

export interface ToolCall {
  toolName: string
  args: Record<string, unknown>
  result?: string
  durationMs: number
}

export interface ChatStreamEvent {
  type: 'text' | 'sources' | 'toolMetadata' | 'done' | 'error'
  content?: string
  sources?: Source[]
  toolMetadata?: ToolCall[]
}

export interface DiagnosticData {
  requestId: string
  latencyMs: number
  chunksRetrieved: number
  toolsCalled: number
  ragEnabled: {
    bm25: boolean
    reranking: boolean
    queryRewrite: boolean
  }
}

export interface ChatStats {
  llmModel: string
  sessionId: string
  chunksCount: number
  projectsCount: number
  ragEnabled: {
    bm25: boolean
    reranking: boolean
    queryRewrite: boolean
  }
}
