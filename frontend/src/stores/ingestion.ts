import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import type { Project, IngestProgressEvent } from '@/api/types/ingestion'

export interface LogEntry {
  type: 'info' | 'processing' | 'error' | 'done'
  message: string
  time: string
}

export const useIngestionStore = defineStore('ingestion', () => {
  const projects = ref<Project[]>([])
  const currentStep = ref<1 | 2 | 3>(1)
  const status = ref<'idle' | 'scanning' | 'ingesting' | 'completed' | 'error' | 'interrupted'>('idle')
  const progress = reactive({
    current: 0,
    total: 0,
    percentage: 0,
    currentFile: '',
    eta: '',
  })
  const logs = reactive<LogEntry[]>([])

  function reset() {
    currentStep.value = 1
    status.value = 'idle'
    progress.current = 0
    progress.total = 0
    progress.percentage = 0
    progress.currentFile = ''
    progress.eta = ''
    logs.length = 0
  }

  return { projects, currentStep, status, progress, logs, reset }
})
