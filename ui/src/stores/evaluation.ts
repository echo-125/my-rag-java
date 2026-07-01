import { defineStore } from 'pinia'
import type { TestSet, TestCase, EvalBatch, EvalResult } from '@/types/evaluation'
import { api } from '@/utils/api'

export const useEvaluationStore = defineStore('evaluation', () => {
  const testSets = ref<TestSet[]>([])
  const activeTestSetId = ref<string | null>(null)
  const testCases = ref<TestCase[]>([])
  const currentBatch = ref<EvalBatch | null>(null)
  const isRunning = ref(false)

  function setActiveTestSet(id: string | null) {
    activeTestSetId.value = id
    testCases.value = []
  }

  async function fetchTestSets() {
    testSets.value = await api<TestSet[]>('/evaluation/testset')
  }

  async function createTestSet(data: { name: string; description: string }) {
    const ts = await api<TestSet>('/evaluation/testset', { method: 'POST', body: data })
    testSets.value.push(ts)
    return ts
  }

  async function deleteTestSet(id: string) {
    await api(`/evaluation/testset/${id}`, { method: 'DELETE' })
    testSets.value = testSets.value.filter(t => t.id !== id)
  }

  async function fetchTestCases(testSetId: string) {
    testCases.value = await api<TestCase[]>(`/evaluation/testset/${testSetId}/cases`)
  }

  async function addTestCase(testSetId: string, data: Partial<TestCase>) {
    const tc = await api<TestCase>(`/evaluation/testset/${testSetId}/cases`, {
      method: 'POST',
      body: data,
    })
    testCases.value.push(tc)
    return tc
  }

  async function deleteTestCase(id: string) {
    await api(`/evaluation/testcase/${id}`, { method: 'DELETE' })
    testCases.value = testCases.value.filter(t => t.id !== id)
  }

  async function startBatch(testSetId: string, topK: number) {
    isRunning.value = true
    const batch = await api<EvalBatch>('/evaluation/run', {
      method: 'POST',
      body: { testSetId, topK },
    })
    currentBatch.value = batch
    return batch
  }

  async function fetchBatchStatus(batchId: string) {
    const batch = await api<EvalBatch>(`/evaluation/run/${batchId}/status`)
    currentBatch.value = batch
    if (batch.status !== 'running' && batch.status !== 'pending') {
      isRunning.value = false
    }
    return batch
  }

  async function cancelBatch(batchId: string) {
    await api(`/evaluation/run/${batchId}/cancel`, { method: 'POST' })
    isRunning.value = false
  }

  return {
    testSets,
    activeTestSetId,
    testCases,
    currentBatch,
    isRunning,
    setActiveTestSet,
    fetchTestSets,
    createTestSet,
    deleteTestSet,
    fetchTestCases,
    addTestCase,
    deleteTestCase,
    startBatch,
    fetchBatchStatus,
    cancelBatch,
  }
})
