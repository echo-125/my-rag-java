import http from './http'
import type { DashboardStats, LanguageStat, QaRecord, EvalReportBrief, FeedbackStats } from './types/dashboard'

/** 获取仪表盘统计 */
export async function getStats(): Promise<DashboardStats> {
  const { data } = await http.get<DashboardStats>('/api/dashboard/stats')
  return data
}

/** 获取语言分布 */
export async function getLanguageStats(): Promise<LanguageStat[]> {
  const { data } = await http.get<LanguageStat[]>('/api/dashboard/language-stats')
  return data
}

/** 获取最近问答 */
export async function getRecentQa(): Promise<QaRecord[]> {
  const { data } = await http.get<QaRecord[]>('/api/dashboard/recent-qa')
  return data
}

/** 获取评估报告摘要 */
export async function getEvalReport(): Promise<EvalReportBrief> {
  const { data } = await http.get<EvalReportBrief>('/api/evaluation/report')
  return data
}

/** 获取反馈统计 */
export async function getFeedbackStats(): Promise<FeedbackStats> {
  const { data } = await http.get<FeedbackStats>('/api/feedback/stats')
  return data
}
