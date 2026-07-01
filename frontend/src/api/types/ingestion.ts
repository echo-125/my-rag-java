/** 项目配置（/api/project-configs） */
export interface Project {
  id: string
  name: string
  path: string
  description?: string
  status: 'pending' | 'completed'
  createdAt: string
  ingestedAt?: string
}

/** 扫描请求中的项目信息 */
export interface ProjectInfo {
  name: string
  path: string
}

/** 扫描结果（/api/ingestion/scan） */
export interface ScanResult {
  success: boolean
  extensions: Array<{
    ext: string
    count: number
  }>
}

/** 入库进度事件 — 来自 /api/ingestion/process 的 data: 行 */
export interface IngestProgressEvent {
  status: 'processing' | 'done' | 'error'
  message?: string
  current?: number
  total?: number
  progressPercentage?: number
  currentFile?: string
}
