import { ref, reactive } from 'vue'
import type { IngestProgressEvent } from '@/api/types/ingestion'

export interface LogEntry {
  type: 'info' | 'processing' | 'error' | 'done'
  message: string
  time: string
}

export function useStreamIngest() {
  const status = ref<'idle' | 'scanning' | 'ingesting' | 'completed' | 'error' | 'interrupted'>('idle')
  const progress = reactive({
    current: 0,
    total: 0,
    percentage: 0,
    currentFile: '',
    eta: '',
  })
  const logs = reactive<LogEntry[]>([])

  let startTime = 0
  let controller: AbortController | null = null

  function addLog(type: LogEntry['type'], message: string) {
    const time = new Date().toLocaleTimeString('en-US', { hour12: false })
    logs.push({ type, message, time })
    if (logs.length > 2000) {
      logs.splice(0, logs.length - 2000)
    }
  }

  async function startProcess(projects: Array<{ name: string; path: string }>, exts: string[]) {
    status.value = 'ingesting'
    progress.current = 0
    progress.total = 0
    progress.percentage = 0
    progress.currentFile = ''
    progress.eta = ''
    logs.length = 0
    startTime = Date.now()
    controller = new AbortController()

    addLog('info', `开始入库，所选类型: ${exts.join(', ')}`)

    try {
      const resp = await fetch('/api/ingestion/process', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projects, exts }),
        signal: controller.signal,
      })

      if (!resp.ok) throw new Error('入库请求失败 HTTP ' + resp.status)

      const reader = resp.body!.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop()!

        for (const line of lines) {
          const t = line.trim()
          if (t.startsWith('data:') && t.length > 5) {
            try {
              const data: IngestProgressEvent = JSON.parse(t.substring(5))
              if (data.message) addLog(data.status || 'info', data.message)

              if (data.current !== undefined && data.total !== undefined) {
                progress.current = data.current
                progress.total = data.total
                progress.percentage = data.progressPercentage || Math.floor((data.current / data.total) * 100)
                if (data.currentFile) progress.currentFile = data.currentFile

                const elapsed = (Date.now() - startTime) / 1000
                const speed = data.current / elapsed
                progress.eta = speed > 0 ? `${Math.floor((data.total - data.current) / speed)}s` : '--'
              }

              if (data.status === 'done') {
                addLog('done', '入库完成')
                status.value = 'completed'
              }
            } catch {}
          }
        }
      }
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        addLog('error', e.message)
        status.value = 'error'
      }
    } finally {
      controller = null
    }
  }

  function cancel() {
    controller?.abort()
    addLog('info', '已取消入库')
    status.value = 'idle'
  }

  return { status, progress, logs, addLog, startProcess, cancel }
}
