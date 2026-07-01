export interface TestSet {
  id: string
  name: string
  description: string
  caseCount: number
  createdAt: string
  updatedAt: string
}

export interface TestCase {
  id: string
  testSetId: string
  question: string
  expectedFiles: string
  tags: string
  createdAt: string
}

export interface EvalBatch {
  id: string
  testSetId: string
  testSetName: string
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled'
  totalCases: number
  completedCases: number
  avgPrecision: number
  avgRecall: number
  avgMrr: number
  avgHitRate: number
  avgLatencyMs: number
  createdAt: string
  completedAt: string | null
}

export interface EvalResult {
  id: string
  batchId: string
  testCaseId: string
  question: string
  retrievedFiles: string[]
  expectedFiles: string[]
  precisionAtK: number
  recall: number
  mrr: number
  hitRate: number
  latencyMs: number
}

export interface EvalHistory {
  batchId: string
  testSetName: string
  avgPrecision: number
  avgRecall: number
  avgMrr: number
  avgHitRate: number
  completedAt: string
}
