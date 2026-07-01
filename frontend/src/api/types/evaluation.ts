/** 测试集（/api/evaluation/testset） */
export interface Testset {
  id: string
  name: string
  description?: string
  caseCount: number
  createdAt: string
}

/** 测试用例（/api/evaluation/testset/:id/cases） */
export interface TestCase {
  id: string
  question: string
  expectedFiles: string   // JSON 字符串
  tags: string           // JSON 字符串
}

/** 评估批次状态（/api/evaluation/run/:id/status） */
export interface BatchStatus {
  batchId: string
  status: 'running' | 'completed' | 'failed' | 'cancelled'
  progress: string
  totalCases: number
  completedCases: number
  error?: string
  result?: EvalReport
}

/** 评估报告 */
export interface EvalReport {
  precisionAtK: number | null
  recall: number | null
  mrr: number | null
  hitRate: number | null
  avgLatencyMs: number | null
  results: EvalResult[]
}

/** 逐题评估结果 */
export interface EvalResult {
  question: string
  hit: boolean
  firstHitRank: number | null
  latencyMs: number | null
  expectedFiles: string   // JSON 字符串
  retrievedFiles: string  // JSON 字符串
  parseWarning?: string
}

/** 历史批次摘要（/api/evaluation/history） */
export interface BatchSummary {
  batchId: string
  precisionAtK: number | null
  recall: number | null
  mrr: number | null
  hitRate: number | null
  evaluatedAt: string | null
  totalCases: number
  completedCases: number
}
