import { ref, onBeforeUnmount } from 'vue'
import { getEvalStatus } from '@/api/evaluation'
import type { BatchStatus } from '@/api/types/evaluation'

export function useEvalPoll() {
  const isPolling = ref(false)
  const progress = ref('')
  const progressPct = ref(0)
  const currentBatchId = ref<string | null>(null)
  const result = ref<BatchStatus | null>(null)

  let timer: ReturnType<typeof setInterval> | null = null

  function startPolling(batchId: string) {
    currentBatchId.value = batchId
    isPolling.value = true
    progress.value = ''
    progressPct.value = 0
    result.value = null
    timer = setInterval(() => pollStatus(batchId), 2000)
  }

  async function pollStatus(batchId: string) {
    try {
      const data = await getEvalStatus(batchId)
      progress.value = data.progress
      if (data.totalCases > 0) {
        progressPct.value = Math.floor((data.completedCases / data.totalCases) * 100)
      }

      if (data.status === 'completed' || data.status === 'failed' || data.status === 'cancelled') {
        stopPolling()
        result.value = data
      }
    } catch {}
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    isPolling.value = false
  }

  onBeforeUnmount(() => {
    stopPolling()
  })

  return { isPolling, progress, progressPct, currentBatchId, result, startPolling, stopPolling }
}
