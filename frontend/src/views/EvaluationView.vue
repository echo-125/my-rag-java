<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NInput } from 'naive-ui'
import {
  getTestsets, createTestset, deleteTestset, getCases, addCase, deleteCase,
  exportTestset, importTestset, runEvaluation, cancelEvaluation, getLatestReport,
} from '@/api/evaluation'
import { useEvalPoll } from '@/composables/useEvalPoll'
import type { Testset, TestCase, EvalReport } from '@/api/types/evaluation'
import TestsetList from '@/components/evaluation/TestsetList.vue'
import EvalMetrics from '@/components/evaluation/EvalMetrics.vue'
import EvalDetail from '@/components/evaluation/EvalDetail.vue'
import TrendChart from '@/components/evaluation/TrendChart.vue'

const testsets = ref<Testset[]>([])
const currentTestsetId = ref<string | null>(null)
const cases = ref<TestCase[]>([])
const evalReport = ref<EvalReport | null>(null)
const evalK = ref('5')
const running = ref(false)

const { isPolling, progress, progressPct, currentBatchId, result, startPolling, stopPolling } = useEvalPoll()

// ─── 测试集管理 ───

async function loadTestsets() {
  testsets.value = await getTestsets()
}

async function handleSelectTestset(id: string) {
  currentTestsetId.value = id
  await loadCases(id)
}

async function handleDeleteTestset(id: string, name: string) {
  if (!confirm(`确定删除测试集 "${name}"？`)) return
  await deleteTestset(id)
  if (currentTestsetId.value === id) {
    currentTestsetId.value = null
    cases.value = []
  }
  await loadTestsets()
}

async function handleCreateTestset(name: string, description: string) {
  await createTestset(name, description)
  await loadTestsets()
}

// ─── 测试用例 ───

async function loadCases(testsetId: string) {
  cases.value = await getCases(testsetId)
}

async function handleAddCase() {
  if (!currentTestsetId.value) return
  const question = prompt('输入测试问题：')
  if (!question) return
  const files = prompt('期望命中的文件路径（逗号分隔）：')
  if (!files) return
  const tags = prompt('标签（逗号分隔，可选）：') || ''
  await addCase(currentTestsetId.value, question, files.split(',').map(f => f.trim()), tags.split(',').map(t => t.trim()).filter(Boolean))
  await loadCases(currentTestsetId.value)
  await loadTestsets()
}

async function handleDeleteCase(id: string) {
  if (!confirm('确定删除此用例？')) return
  await deleteCase(id)
  if (currentTestsetId.value) {
    await loadCases(currentTestsetId.value)
    await loadTestsets()
  }
}

// ─── 导入导出 ───

async function handleExport(id: string) {
  try {
    const blob = await exportTestset(id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'testset.json'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e: any) {
    window.alert('导出失败: ' + e.message)
  }
}

async function handleImport(file: File) {
  try {
    const text = await file.text()
    const data = JSON.parse(text)
    const result = await importTestset(data)
    window.alert(`导入成功：${result.name}，${result.importedCases} 条用例`)
    await loadTestsets()
  } catch (e: any) {
    window.alert('导入失败: ' + e.message)
  }
}

// ─── 评估执行 ───

async function handleRunEval() {
  if (!currentTestsetId.value) return
  running.value = true
  try {
    const { batchId } = await runEvaluation(currentTestsetId.value, parseInt(evalK.value) || 5)
    startPolling(batchId)
  } catch (e: any) {
    window.alert('启动评估失败: ' + e.message)
    running.value = false
  }
}

async function handleCancelEval() {
  if (currentBatchId.value) {
    await cancelEvaluation(currentBatchId.value)
    stopPolling()
    running.value = false
  }
}

// 监听评估完成
import { watch } from 'vue'
watch(result, async (r) => {
  if (r?.status === 'completed') {
    running.value = false
    const report = await getLatestReport()
    if (report.found) evalReport.value = report
    window.alert('评估完成')
  } else if (r?.status === 'failed') {
    running.value = false
    window.alert('评估失败: ' + (r.error || ''))
  } else if (r?.status === 'cancelled') {
    running.value = false
    window.alert('评估已取消')
  }
})

onMounted(() => {
  loadTestsets()
  getLatestReport().then(r => { if (r.found) evalReport.value = r }).catch(() => {})
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-6" style="background: var(--bg-page)">
    <div class="max-w-[1600px] mx-auto">
      <div class="mb-6 flex items-center justify-between">
        <div>
          <h1 class="text-xl font-bold" style="color: var(--text-1)">评估中心</h1>
          <p class="text-sm mt-1" style="color: var(--text-3)">管理测试集、执行检索评估、查看指标报告</p>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <!-- 左侧：测试集管理 -->
        <div class="lg:col-span-1 flex flex-col gap-4">
          <TestsetList
            :testsets="testsets"
            :current-id="currentTestsetId"
            @select="handleSelectTestset"
            @delete="handleDeleteTestset"
            @create="handleCreateTestset"
            @export="handleExport"
            @import="handleImport"
          />

          <!-- 测试用例 -->
          <div class="rounded-xl shadow-sm flex flex-col overflow-hidden flex-1" style="background: var(--bg-card); border: 1px solid var(--border)">
            <div class="p-4 flex items-center justify-between" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
              <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
                <div class="w-1.5 h-4 rounded-full" style="background: #9333ea"></div>
                测试用例 <span class="text-xs font-normal" style="color: var(--text-4)">({{ cases.length }})</span>
              </h2>
              <NButton size="tiny" :disabled="!currentTestsetId" @click="handleAddCase">+ 添加</NButton>
            </div>
            <div class="overflow-y-auto flex-1">
              <div v-if="cases.length === 0" class="text-sm text-center py-6" style="color: var(--text-4)">
                {{ currentTestsetId ? '暂无用例' : '请先选择测试集' }}
              </div>
              <div
                v-for="c in cases"
                :key="c.id"
                class="px-4 py-3 transition-colors"
                style="border-bottom: 1px solid var(--border-subtle)"
              >
                <div class="flex items-start justify-between gap-2">
                  <div class="flex-1 min-w-0">
                    <div class="text-sm" style="color: var(--text-1)">{{ c.question }}</div>
                    <div class="text-xs mt-1" style="color: var(--text-4)">
                      期望文件：{{ (() => { try { return JSON.parse(c.expectedFiles || '[]').map((f: string) => f.split('/').pop()).join(', ') } catch { return '—' } })() }}
                    </div>
                  </div>
                  <button class="text-xs p-1 rounded flex-shrink-0 transition-colors" style="color: var(--text-4)" @click="handleDeleteCase(c.id)">✕</button>
                </div>
              </div>
            </div>
          </div>

          <!-- 执行评估 -->
          <div class="rounded-xl p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <label class="text-xs mb-1 block" style="color: var(--text-3)">K 值（检索 Top-K）</label>
                <NInput v-model:value="evalK" type="text" size="small" />
              </div>
              <NButton
                type="primary"
                :disabled="!currentTestsetId || running"
                :loading="running"
                @click="handleRunEval"
                class="mt-5"
              >执行评估</NButton>
              <NButton
                v-if="isPolling"
                type="error"
                ghost
                size="small"
                @click="handleCancelEval"
                class="mt-5"
              >取消</NButton>
            </div>
          </div>
        </div>

        <!-- 右侧：评估结果 -->
        <div class="lg:col-span-2 flex flex-col gap-4">
          <!-- 评估进度 -->
          <div v-if="isPolling" class="rounded-xl p-5" style="background: var(--bg-card); border: 1px solid var(--border)">
            <div class="flex items-center justify-between text-sm mb-3">
              <span class="font-medium" style="color: var(--text-1)">评估中... {{ progress }}</span>
              <span style="color: var(--text-3)">{{ progressPct }}%</span>
            </div>
            <div class="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
              <div class="h-2 rounded-full transition-all duration-300" :style="{ width: progressPct + '%', background: 'var(--c-primary)' }"></div>
            </div>
          </div>

          <!-- 指标卡片 -->
          <EvalMetrics
            :precision-at-k="evalReport?.precisionAtK ?? null"
            :recall="evalReport?.recall ?? null"
            :mrr="evalReport?.mrr ?? null"
            :hit-rate="evalReport?.hitRate ?? null"
          />

          <!-- 历史趋势 -->
          <TrendChart />

          <!-- 逐题明细 -->
          <EvalDetail :results="evalReport?.results || []" />
        </div>
      </div>
    </div>
  </div>
</template>
