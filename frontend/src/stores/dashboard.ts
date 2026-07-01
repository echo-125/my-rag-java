import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DashboardStats, QaRecord, FeedbackStats, EvalReportBrief } from '@/api/types/dashboard'

export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<DashboardStats | null>(null)
  const recentQa = ref<QaRecord[]>([])
  const feedbackStats = ref<FeedbackStats | null>(null)
  const evalReport = ref<EvalReportBrief | null>(null)
  const loading = ref(false)

  return { stats, recentQa, feedbackStats, evalReport, loading }
})
