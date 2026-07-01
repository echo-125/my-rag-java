export interface DashboardStats {
  totalChunks: number
  totalFiles: number
  totalProjects: number
  lastIngestionTime: string | null
}

export interface LanguageStat {
  language: string
  count: number
  percentage: number
}

export interface RecentQA {
  id: string
  question: string
  answer: string
  modelUsed: string
  createdAt: string
}

export interface EvaluationReport {
  totalBatches: number
  avgPrecision: number
  avgRecall: number
  avgMrr: number
  avgHitRate: number
  avgLatencyMs: number
}

export interface FeedbackStats {
  total: number
  positive: number
  negative: number
  satisfactionRate: number
}
