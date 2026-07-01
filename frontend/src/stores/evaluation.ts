import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Testset, TestCase, EvalReport, BatchSummary } from '@/api/types/evaluation'

export const useEvaluationStore = defineStore('evaluation', () => {
  const testsets = ref<Testset[]>([])
  const currentTestsetId = ref<string | null>(null)
  const cases = ref<TestCase[]>([])
  const currentBatchId = ref<string | null>(null)
  const isPolling = ref(false)
  const evalReport = ref<EvalReport | null>(null)
  const history = ref<BatchSummary[]>([])

  return { testsets, currentTestsetId, cases, currentBatchId, isPolling, evalReport, history }
})
