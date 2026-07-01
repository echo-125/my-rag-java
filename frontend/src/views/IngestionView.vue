<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NModal } from 'naive-ui'
import { getProjects, deleteProject, deleteChunks, scanFiles, getChunkCount } from '@/api/ingestion'
import { useStreamIngest } from '@/composables/useStreamIngest'
import type { Project, ScanResult } from '@/api/types/ingestion'
import ProjectForm from '@/components/ingestion/ProjectForm.vue'
import ProjectList from '@/components/ingestion/ProjectList.vue'
import FileScanner from '@/components/ingestion/FileScanner.vue'
import IngestProgress from '@/components/ingestion/IngestProgress.vue'
import TerminalLog from '@/components/ingestion/TerminalLog.vue'

const projects = ref<Project[]>([])
const chunkCounts = ref<Record<string, number>>({})
const currentStep = ref<1 | 2 | 3>(1)
const scanResult = ref<ScanResult | null>(null)
const currentProject = ref<{ name: string; path: string } | null>(null)
const detailModalVisible = ref(false)
const detailProject = ref<Project | null>(null)

const { status, progress, logs, startProcess, cancel } = useStreamIngest()

async function loadProjects() {
  try {
    projects.value = await getProjects()
    // 加载 chunk 数量
    const counts: Record<string, number> = {}
    for (const p of projects.value) {
      try {
        counts[p.name] = await getChunkCount(p.name)
      } catch {
        counts[p.name] = 0
      }
    }
    chunkCounts.value = counts
  } catch {}
}

async function handleClearAll() {
  if (confirm('此操作将清空所有 chunks 数据，且无法恢复。请输入确认')) {
    const r = await deleteChunks('')
    window.alert('已清空 ' + r.deleted + ' 个 chunks')
    await loadProjects()
  }
}

async function handleDelete(project: Project) {
  if (!confirm(`确定删除项目 "${project.name}" 及其所有 chunks？`)) return
  try {
    await deleteChunks(project.name)
    await deleteProject(project.id)
    await loadProjects()
  } catch (e: any) {
    window.alert('删除失败: ' + e.message)
  }
}

async function handleReIngest(project: Project) {
  currentProject.value = { name: project.name, path: project.path }
  currentStep.value = 2

  try {
    const result = await scanFiles([currentProject.value])
    scanResult.value = result
    if (!result.extensions || result.extensions.length === 0) {
      window.alert('未发现可处理的文件')
      currentStep.value = 1
      return
    }
  } catch (e: any) {
    window.alert('扫描失败: ' + e.message)
    currentStep.value = 1
  }
}

async function handleAddProject() {
  await loadProjects()
  // 自动触发扫描最后一个项目
  const last = projects.value[projects.value.length - 1]
  if (last) {
    await handleReIngest(last)
  }
}

async function handleConfirmTypes(exts: string[]) {
  if (!currentProject.value) return
  currentStep.value = 3

  // 入库前清空旧 chunks
  try {
    const deleted = await deleteChunks(currentProject.value.name)
    if (deleted.deleted > 0) {
      logs.push({ type: 'info', message: `已清空 "${currentProject.value.name}" 的 ${deleted.deleted} 个旧 chunks`, time: new Date().toLocaleTimeString('en-US', { hour12: false }) })
    }
  } catch {}

  await startProcess([currentProject.value], exts)
  await loadProjects()
}

function handleViewDetail(project: Project) {
  detailProject.value = project
  detailModalVisible.value = true
}

onMounted(() => {
  loadProjects()
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-6" style="background: var(--bg-page)">
    <div class="max-w-[1600px] mx-auto h-full flex flex-col lg:flex-row gap-4">

      <!-- 左侧：项目配置区 -->
      <div class="w-full lg:w-[35%] rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
        <div class="p-4" style="border-bottom: 1px solid var(--border)">
          <h2 class="text-sm font-semibold" style="color: var(--text-1)">目标项目配置</h2>
          <p class="text-xs mt-1" style="color: var(--text-3)">添加需要被知识库索引的本地项目目录</p>
        </div>

        <ProjectForm @created="handleAddProject" />

        <div class="flex-1 overflow-y-auto p-4" style="background: var(--bg-elevated)">
          <div class="text-xs font-medium uppercase tracking-wider mb-3" style="color: var(--text-4)">已入库项目</div>
          <ProjectList
            v-if="projects.length > 0"
            :projects="projects"
            :chunk-counts="chunkCounts"
            @view="handleViewDetail"
            @re-ingest="handleReIngest"
            @delete="handleDelete"
          />
          <div v-else class="text-center text-sm py-8" style="color: var(--text-4)">暂无项目，请在上方添加</div>
        </div>

        <div class="p-4" style="border-top: 1px solid var(--border); background: var(--bg-elevated)">
          <NButton block type="error" ghost @click="handleClearAll">
            清空全部知识库
          </NButton>
        </div>
      </div>

      <!-- 右侧：工作流区 -->
      <div class="w-full lg:w-[65%] rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">

        <!-- 步骤导航头部 -->
        <div class="flex items-center justify-between p-4" style="border-bottom: 1px solid var(--border); background: var(--bg-elevated)">
          <div class="flex items-center gap-6 text-sm font-medium">
            <div :style="{ color: currentStep >= 2 ? '#16a34a' : currentStep === 1 ? 'var(--c-primary)' : 'var(--text-4)' }" class="flex items-center gap-2">
              <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs" :style="{ background: currentStep > 1 ? '#dcfce7' : currentStep === 1 ? '#d1fae5' : 'var(--bg-page)' }">
                <template v-if="currentStep > 1">✓</template>
                <template v-else>1</template>
              </span>
              路径校验
            </div>
            <div class="w-8 h-px" style="background: var(--border)"></div>
            <div :style="{ color: currentStep > 2 ? '#16a34a' : currentStep === 2 ? 'var(--c-primary)' : 'var(--text-4)' }" class="flex items-center gap-2">
              <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs" :style="{ background: currentStep > 2 ? '#dcfce7' : currentStep === 2 ? '#d1fae5' : 'var(--bg-page)' }">
                <template v-if="currentStep > 2">✓</template>
                <template v-else>2</template>
              </span>
              类型筛选
            </div>
            <div class="w-8 h-px" style="background: var(--border)"></div>
            <div :style="{ color: currentStep === 3 ? 'var(--c-primary)' : 'var(--text-4)' }" class="flex items-center gap-2">
              <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs" :style="{ background: currentStep === 3 ? '#d1fae5' : 'var(--bg-page)' }">3</span>
              入库处理
            </div>
          </div>
        </div>

        <!-- 步骤内容区 -->
        <div class="flex-1 p-6 overflow-y-auto relative" style="background: var(--bg-card)">

          <!-- Step 1: 初始空状态 -->
          <div v-show="currentStep === 1" class="flex flex-col items-center justify-center text-center p-6 h-full">
            <div class="w-16 h-16 rounded-full flex items-center justify-center text-gray-300 mb-4" style="background: var(--bg-elevated); border: 1px solid var(--border)">
              <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
              </svg>
            </div>
            <h3 class="text-lg font-medium mb-1" style="color: var(--text-1)">等待执行</h3>
            <p class="text-sm max-w-sm" style="color: var(--text-3)">请在左侧添加项目后，点击重新入库。系统将扫描可索引的文件类型。</p>
          </div>

          <!-- Step 2: 文件类型选择 -->
          <div v-show="currentStep === 2" class="h-full flex flex-col">
            <FileScanner :scan-result="scanResult" @confirm="handleConfirmTypes" />
          </div>

          <!-- Step 3: 进度条与日志 -->
          <div v-show="currentStep === 3" class="h-full flex flex-col gap-4">
            <IngestProgress
              :percentage="progress.percentage"
              :current="progress.current"
              :total="progress.total"
              :current-file="progress.currentFile"
              :eta="progress.eta"
              :status="status"
            />
            <TerminalLog :logs="logs" class="flex-1 rounded-xl border" style="border-color: var(--border)" />
          </div>

        </div>
      </div>
    </div>
  </div>

  <!-- 项目详情模态框 -->
  <NModal v-model:show="detailModalVisible">
    <div class="rounded-xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto p-5" style="background: var(--bg-card)">
      <div class="flex items-center justify-between mb-4" style="border-bottom: 1px solid var(--border); padding-bottom: 12px">
        <h3 class="text-base font-semibold" style="color: var(--text-1)">项目详情</h3>
        <button @click="detailModalVisible = false" class="text-lg" style="color: var(--text-4)">&times;</button>
      </div>
      <div v-if="detailProject" class="space-y-3">
        <div class="flex gap-3"><span class="text-sm w-20" style="color: var(--text-3)">名称</span><span class="text-sm font-medium" style="color: var(--text-1)">{{ detailProject.name }}</span></div>
        <div class="flex gap-3"><span class="text-sm w-20" style="color: var(--text-3)">路径</span><span class="text-sm font-mono break-all" style="color: var(--text-1)">{{ detailProject.path }}</span></div>
        <div class="flex gap-3"><span class="text-sm w-20" style="color: var(--text-3)">状态</span><span class="text-sm font-medium" :style="{ color: detailProject.status === 'completed' ? '#16a34a' : '#d97706' }">{{ detailProject.status === 'completed' ? '已入库' : '待入库' }}</span></div>
        <div class="flex gap-3"><span class="text-sm w-20" style="color: var(--text-3)">入库时间</span><span class="text-sm" style="color: var(--text-1)">{{ detailProject.ingestedAt ? new Date(detailProject.ingestedAt).toLocaleString() : '—' }}</span></div>
      </div>
    </div>
  </NModal>
</template>
