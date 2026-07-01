import http from './http'
import type { Testset, TestCase, BatchStatus, BatchSummary, EvalReport } from './types/evaluation'

/** 获取测试集列表 */
export async function getTestsets(): Promise<Testset[]> {
  const { data } = await http.get<Testset[]>('/api/evaluation/testset')
  return data
}

/** 创建测试集 */
export async function createTestset(name: string, description?: string): Promise<Testset> {
  const { data } = await http.post<Testset>('/api/evaluation/testset', { name, description })
  return data
}

/** 删除测试集 */
export async function deleteTestset(id: string): Promise<void> {
  await http.delete(`/api/evaluation/testset/${id}`)
}

/** 获取测试用例 */
export async function getCases(testsetId: string): Promise<TestCase[]> {
  const { data } = await http.get<TestCase[]>(`/api/evaluation/testset/${testsetId}/cases`)
  return data
}

/** 添加测试用例 */
export async function addCase(testsetId: string, question: string, expectedFiles: string[], tags: string[]): Promise<void> {
  await http.post(`/api/evaluation/testset/${testsetId}/cases`, {
    question,
    expectedFiles: JSON.stringify(expectedFiles),
    tags: JSON.stringify(tags),
  })
}

/** 删除测试用例 */
export async function deleteCase(testcaseId: string): Promise<void> {
  await http.delete(`/api/evaluation/testcase/${testcaseId}`)
}

/** 导出测试集 */
export async function exportTestset(id: string): Promise<Blob> {
  const { data } = await http.get(`/api/evaluation/testset/${id}/export`, { responseType: 'blob' })
  return data
}

/** 导入测试集 */
export async function importTestset(payload: unknown): Promise<{ name: string; importedCases: number }> {
  const { data } = await http.post<{ name: string; importedCases: number }>('/api/evaluation/testset/import', payload)
  return data
}

/** 启动评估 */
export async function runEvaluation(testsetId: string, k: number): Promise<{ batchId: string }> {
  const { data } = await http.post<{ batchId: string }>('/api/evaluation/run', { testsetId, k })
  return data
}

/** 查询评估状态 */
export async function getEvalStatus(batchId: string): Promise<BatchStatus> {
  const { data } = await http.get<BatchStatus>(`/api/evaluation/run/${batchId}/status`)
  return data
}

/** 取消评估 */
export async function cancelEvaluation(batchId: string): Promise<void> {
  await http.post(`/api/evaluation/run/${batchId}/cancel`)
}

/** 获取最新评估报告 */
export async function getLatestReport(): Promise<EvalReport & { found: boolean }> {
  const { data } = await http.get<EvalReport & { found: boolean }>('/api/evaluation/report')
  return data
}

/** 获取历史趋势 */
export async function getEvalHistory(): Promise<BatchSummary[]> {
  const { data } = await http.get<BatchSummary[]>('/api/evaluation/history')
  return data
}
