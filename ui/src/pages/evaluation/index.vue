<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { useEvaluationStore } from '@/stores/evaluation'
import { toast } from 'vue-sonner'
import { api } from '@/utils/api'
import { usePolling } from '@/composables/usePolling'
import type { EvalBatch, EvalHistory } from '@/types/evaluation'

const evalStore = useEvaluationStore()

const activeTab = ref<'testsets' | 'run' | 'history'>('testsets')
const showCreateModal = ref(false)
const newTestSet = ref({ name: '', description: '' })
const showCaseModal = ref(false)
const newCase = ref({ question: '', expectedFiles: '', tags: '' })
const topK = ref(5)
const evalHistory = ref<EvalHistory[]>([])

const { start: startPolling, stop: stopPolling } = usePolling(async () => {
  if (evalStore.currentBatch) {
    const batch = await evalStore.fetchBatchStatus(evalStore.currentBatch.id)
    if (batch.status !== 'running' && batch.status !== 'pending') {
      stopPolling()
      if (batch.status === 'completed') {
        toast.success('评估完成')
      } else if (batch.status === 'failed') {
        toast.error('评估失败')
      }
    }
  }
}, 2000)

async function handleCreateTestSet() {
  try {
    await evalStore.createTestSet(newTestSet.value)
    toast.success('测试集创建成功')
    showCreateModal.value = false
    newTestSet.value = { name: '', description: '' }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '创建失败')
  }
}

async function handleDeleteTestSet(id: string) {
  await evalStore.deleteTestSet(id)
  toast.success('已删除')
}

async function handleSelectTestSet(id: string) {
  evalStore.setActiveTestSet(id)
  await evalStore.fetchTestCases(id)
}

async function handleAddCase() {
  if (!evalStore.activeTestSetId) return
  await evalStore.addTestCase(evalStore.activeTestSetId, newCase.value)
  toast.success('用例添加成功')
  showCaseModal.value = false
  newCase.value = { question: '', expectedFiles: '', tags: '' }
}

async function handleDeleteCase(id: string) {
  await evalStore.deleteTestCase(id)
  toast.success('已删除')
}

async function handleStartEval() {
  if (!evalStore.activeTestSetId) {
    toast.warning('请先选择测试集')
    return
  }
  try {
    await evalStore.startBatch(evalStore.activeTestSetId, topK.value)
    startPolling()
    activeTab.value = 'run'
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '启动失败')
  }
}

async function handleCancelEval() {
  if (evalStore.currentBatch) {
    await evalStore.cancelBatch(evalStore.currentBatch.id)
    stopPolling()
    toast.info('已取消')
  }
}

async function loadHistory() {
  try {
    evalHistory.value = await api<EvalHistory[]>('/evaluation/history')
  } catch {
    evalHistory.value = []
  }
}

async function handleExport() {
  if (!evalStore.activeTestSetId) return
  try {
    const { _data } = await api.raw(`/evaluation/testset/${evalStore.activeTestSetId}/export`)
    const blob = new Blob([_data as BlobPart], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `testset-${evalStore.activeTestSetId}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    toast.error('导出失败')
  }
}

watch(activeTab, (tab) => {
  if (tab === 'history') loadHistory()
})

onMounted(() => {
  evalStore.fetchTestSets()
})
</script>

<template>
  <div class="flex-1 overflow-hidden flex flex-col">
    <!-- Tab 栏 -->
    <div class="flex items-center gap-1 border-b border-default px-4 py-2">
      <button
        v-for="tab in [
          { key: 'testsets', label: '测试集管理' },
          { key: 'run', label: '执行评估' },
          { key: 'history', label: '历史趋势' },
        ]"
        :key="tab.key"
        class="px-3 py-1.5 text-xs rounded-lg transition-colors"
        :class="activeTab === tab.key ? 'bg-primary/15 text-primary font-medium' : 'text-muted hover:bg-elevated'"
        @click="activeTab = tab.key as typeof activeTab"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 测试集管理 -->
    <div v-if="activeTab === 'testsets'" class="flex-1 overflow-y-auto p-4">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-medium">测试集</h3>
        <div class="flex gap-2">
          <UButton size="xs" color="neutral" variant="outline" icon="lucide:download" @click="handleExport">
            导出
          </UButton>
          <UButton size="xs" color="primary" icon="lucide:plus" @click="showCreateModal = true">
            新建
          </UButton>
        </div>
      </div>

      <!-- 测试集列表 -->
      <div class="space-y-2 mb-6">
        <div
          v-for="ts in evalStore.testSets"
          :key="ts.id"
          class="flex items-center justify-between rounded-xl border p-3 transition-colors cursor-pointer"
          :class="evalStore.activeTestSetId === ts.id
            ? 'border-primary bg-primary/5'
            : 'border-default bg-elevated hover:border-primary/30'"
          @click="handleSelectTestSet(ts.id)"
        >
          <div>
            <div class="text-sm font-medium">{{ ts.name }}</div>
            <div class="text-[11px] text-muted mt-0.5">{{ ts.description }} · {{ ts.caseCount }} 用例</div>
          </div>
          <div class="flex items-center gap-1">
            <UButton icon="lucide:trash-2" size="xs" color="error" variant="ghost" @click.stop="handleDeleteTestSet(ts.id)" />
          </div>
        </div>
      </div>

      <!-- 测试用例列表 -->
      <div v-if="evalStore.activeTestSetId" class="border-t border-default pt-4">
        <div class="flex items-center justify-between mb-3">
          <h4 class="text-sm font-medium">测试用例</h4>
          <div class="flex gap-2">
            <UButton size="xs" color="primary" icon="lucide:plus" @click="showCaseModal = true">添加用例</UButton>
            <UButton size="xs" color="primary" variant="solid" icon="lucide:play" @click="handleStartEval">执行评估</UButton>
          </div>
        </div>
        <div class="rounded-xl border border-default overflow-hidden">
          <table class="w-full text-xs">
            <thead>
              <tr class="bg-elevated">
                <th class="px-3 py-2 text-left text-muted font-medium">问题</th>
                <th class="px-3 py-2 text-left text-muted font-medium">期望文件</th>
                <th class="px-3 py-2 text-left text-muted font-medium">标签</th>
                <th class="px-3 py-2 w-10"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="tc in evalStore.testCases" :key="tc.id" class="border-t border-default hover:bg-elevated/50">
                <td class="px-3 py-2 max-w-[300px] truncate">{{ tc.question }}</td>
                <td class="px-3 py-2 font-mono text-muted max-w-[200px] truncate">{{ tc.expectedFiles }}</td>
                <td class="px-3 py-2 text-muted">{{ tc.tags }}</td>
                <td class="px-3 py-2">
                  <UButton icon="lucide:x" size="xs" color="error" variant="ghost" @click="handleDeleteCase(tc.id)" />
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="evalStore.testCases.length === 0" class="text-center py-8 text-xs text-muted">
            暂无测试用例
          </div>
        </div>
      </div>
    </div>

    <!-- 执行评估 -->
    <div v-if="activeTab === 'run'" class="flex-1 overflow-y-auto p-4">
      <div v-if="evalStore.currentBatch" class="max-w-2xl mx-auto space-y-6">
        <!-- 进度 -->
        <div class="rounded-xl border border-default bg-elevated p-4 space-y-3">
          <div class="flex items-center justify-between">
            <span class="text-sm font-medium">{{ evalStore.currentBatch.testSetName }}</span>
            <StatusBadge
              :active="evalStore.currentBatch.status === 'running'"
              :active-label="evalStore.currentBatch.status"
              :inactive-label="evalStore.currentBatch.status"
            />
          </div>
          <div class="h-2 rounded-full bg-default overflow-hidden">
            <div
              class="h-full bg-primary rounded-full transition-all"
              :style="{ width: `${evalStore.currentBatch.totalCases > 0 ? (evalStore.currentBatch.completedCases / evalStore.currentBatch.totalCases) * 100 : 0}%` }"
            />
          </div>
          <div class="text-xs text-muted font-mono text-center">
            {{ evalStore.currentBatch.completedCases }} / {{ evalStore.currentBatch.totalCases }}
          </div>
          <div v-if="evalStore.currentBatch.status === 'running'" class="flex justify-center">
            <UButton color="error" variant="outline" size="xs" @click="handleCancelEval">取消</UButton>
          </div>
        </div>

        <!-- 指标卡片 -->
        <div v-if="evalStore.currentBatch.status === 'completed'" class="grid grid-cols-4 gap-3">
          <div class="rounded-xl border border-default bg-elevated p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalStore.currentBatch.avgPrecision * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">Precision@K</div>
          </div>
          <div class="rounded-xl border border-default bg-elevated p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalStore.currentBatch.avgRecall * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">Recall</div>
          </div>
          <div class="rounded-xl border border-default bg-elevated p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalStore.currentBatch.avgMrr * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">MRR</div>
          </div>
          <div class="rounded-xl border border-default bg-elevated p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalStore.currentBatch.avgHitRate * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">Hit Rate</div>
          </div>
        </div>
      </div>
      <div v-else class="flex flex-col items-center justify-center h-full text-center">
        <UIcon name="lucide:play-circle" class="h-12 w-12 text-muted mb-4" />
        <p class="text-sm text-muted">选择测试集后点击「执行评估」开始</p>
      </div>
    </div>

    <!-- 历史趋势 -->
    <div v-if="activeTab === 'history'" class="flex-1 overflow-y-auto p-4">
      <h3 class="text-sm font-medium mb-4">评估历史趋势</h3>
      <div v-if="evalHistory.length > 0" class="space-y-2">
        <div
          v-for="(h, i) in evalHistory"
          :key="i"
          class="flex items-center justify-between rounded-xl border border-default bg-elevated p-3"
        >
          <div>
            <span class="text-sm font-medium">{{ h.testSetName }}</span>
            <span class="text-[10px] text-muted ml-2 font-mono">{{ h.completedAt }}</span>
          </div>
          <div class="flex gap-3 text-xs font-mono">
            <span>P={{ (h.avgPrecision * 100).toFixed(1) }}%</span>
            <span>R={{ (h.avgRecall * 100).toFixed(1) }}%</span>
            <span>M={{ (h.avgMrr * 100).toFixed(1) }}%</span>
            <span>H={{ (h.avgHitRate * 100).toFixed(1) }}%</span>
          </div>
        </div>
      </div>
      <div v-else class="text-center py-16 text-sm text-muted">暂无历史记录</div>
    </div>

    <!-- 创建测试集模态框 -->
    <UModal v-model:open="showCreateModal" title="新建测试集">
      <template #body>
        <div class="space-y-4 p-2">
          <UFormField label="名称">
            <UInput v-model="newTestSet.name" placeholder="测试集名称" />
          </UFormField>
          <UFormField label="描述">
            <UTextarea v-model="newTestSet.description" placeholder="可选描述" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <div class="flex justify-end gap-2">
          <UButton color="neutral" variant="ghost" @click="showCreateModal = false">取消</UButton>
          <UButton color="primary" :disabled="!newTestSet.name" @click="handleCreateTestSet">创建</UButton>
        </div>
      </template>
    </UModal>

    <!-- 添加用例模态框 -->
    <UModal v-model:open="showCaseModal" title="添加测试用例">
      <template #body>
        <div class="space-y-4 p-2">
          <UFormField label="问题">
            <UInput v-model="newCase.question" placeholder="用户问题" />
          </UFormField>
          <UFormField label="期望命中文件">
            <UInput v-model="newCase.expectedFiles" placeholder="file1.md, file2.java" class="font-mono text-xs" />
          </UFormField>
          <UFormField label="标签">
            <UInput v-model="newCase.tags" placeholder="tag1, tag2" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <div class="flex justify-end gap-2">
          <UButton color="neutral" variant="ghost" @click="showCaseModal = false">取消</UButton>
          <UButton color="primary" :disabled="!newCase.question" @click="handleAddCase">添加</UButton>
        </div>
      </template>
    </UModal>
  </div>
</template>
