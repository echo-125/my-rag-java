/** 仪表盘统计（/api/dashboard/stats） */
export interface DashboardStats {
  totalChunks: number
  fileCount: number
  projectCount: number
  lastProcessTime: string | null
}

/** 语言分布（/api/dashboard/language-stats） */
export interface LanguageStat {
  language: string
  count: number
}

/** 最近问答记录（/api/dashboard/recent-qa） */
export interface QaRecord {
  id: string
  question: string
  answer: string
  modelName: string
  createdAt: string
}

/** 评估报告摘要（/api/evaluation/report） */
export interface EvalReportBrief {
  found: boolean
  precisionAtK?: number
  hitRate?: number
  mrr?: number
  avgLatencyMs?: number
  completedCases?: number
  totalCases?: number
  evaluatedAt?: string
}

/** 反馈统计（/api/feedback/stats） */
export interface FeedbackStats {
  total: number
  positive: number
  negative: number
  positiveRate: number
}
