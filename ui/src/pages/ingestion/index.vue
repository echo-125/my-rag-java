<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useIngestionStore } from '@/stores/ingestion'
import { toast } from 'vue-sonner'
import { api } from '@/utils/api'
import { formatDuration, formatNumber } from '@/utils/format'
import { useSSE } from '@/composables/useSSE'
import { usePolling } from '@/composables/usePolling'
import type { IngestionProgress } from '@/types/ingestion'

const ingestionStore = useIngestionStore()
const sse = useSSE()

const showCreateModal = ref(false)
const newProject = ref({ name: '', path: '', filePatterns: '*.*' })
const activeStep = ref<1 | 2 | 3>(1)
const scanResult = ref<{ totalFiles: number; fileTypes: { extension: string; count: number; selected: boolean }[] } | null>(null)
const progress = ref<IngestionProgress | null>(null)
const logLines = ref<string[]>([])
// 使用 store 的 activeProjectId 替代本地变量
const startTime = ref(0)

const logContainerRef = ref<HTMLElement | null>(null)

function scrollLogs() {
  if (logContainerRef.value) {
    logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
  }
}

async function handleCreateProject() {
  try {
    const p = await ingestionStore.createProject(newProject.value)
    toast.success(`项目 "${p.name}" 创建成功`)
    showCreateModal.value = false
    newProject.value = { name: '', path: '', filePatterns: '*.*' }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '创建失败')
  }
}

async function handleDeleteProject(id: string) {
  try {
    await ingestionStore.deleteProject(id)
    toast.success('项目已删除')
  } catch {
    toast.error('删除失败')
  }
}

async function startScan(projectId: string) {
  ingestionStore.setActiveProject(projectId)
  activeStep.value = 2
  try {
    const result = await api<{ totalFiles: number; fileTypes: { extension: string; count: number; selected: boolean }[] }>(
      '/ingestion/scan',
      { method: 'POST', body: { projectId, filePatterns: '*.*' } },
    )
    // 深拷贝避免直接修改 API 响应对象
    scanResult.value = {
      totalFiles: result.totalFiles,
      fileTypes: result.fileTypes.map(ft => ({ ...ft, selected: true })),
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '扫描失败')
    activeStep.value = 1
  }
}

async function startIngestion() {
  if (!ingestionStore.activeProjectId || !scanResult.value) return
  activeStep.value = 3
  logLines.value = []
  startTime.value = Date.now()
  progress.value = null

  const selectedTypes = scanResult.value.fileTypes
    .filter(ft => ft.selected)
    .map(ft => ft.extension)

  sse.connect('/ingestion/process', {
    projectId: ingestionStore.activeProjectId,
    fileTypes: selectedTypes,
  }, {
    onMessage(data: Record<string, unknown>) {
      const msg = data as unknown as IngestionProgress
      progress.value = msg
      if (msg.message) {
        logLines.value.push(`[${new Date().toLocaleTimeString()}] ${msg.message}`)
        scrollLogs()
      }
      if (msg.status === 'completed') {
        toast.success('入库完成')
        ingestionStore.fetchProjects()
      } else if (msg.status === 'error') {
        toast.error('入库出错')
      }
    },
    onError(err) {
      toast.error(`入库失败: ${err.message}`)
    },
  })
}

function handleCancel() {
  sse.disconnect()
  toast.info('已取消入库')
}

const eta = computed(() => {
  if (!progress.value || progress.value.current === 0) return '计算中...'
  const elapsed = Date.now() - startTime.value
  const remaining = ((progress.value.total - progress.value.current) / progress.value.current) * elapsed
  return formatDuration(remaining)
})

function resetWorkflow() {
  activeStep.value = 1
  scanResult.value = null
  progress.value = null
  logLines.value = []
  ingestionStore.setActiveProject(null)
}

onMounted(() => {
  ingestionStore.fetchProjects()
})
</script>

<template>
  <div class="flex flex-1 overflow-hidden">
    <!-- 左侧：项目配置 (35%) -->
    <div class="w-[35%] shrink-0 border-r border-default bg-default overflow-y-auto">
      <div class="flex items-center justify-between px-4 py-3 border-b border-default">
        <span class="text-sm font-medium">项目配置</span>
        <UButton icon="lucide:plus" size="xs" color="primary" @click="showCreateModal = true" />
      </div>
      <div class="p-3 space-y-2">
        <div v-if="ingestionStore.projects.length === 0" class="text-center py-12 text-sm text-muted">
          暂无项目，点击 + 创建
        </div>
        <div
          v-for="project in ingestionStore.projects"
          :key="project.id"
          class="rounded-xl border p-3 transition-colors cursor-pointer"
          :class="ingestionStore.activeProjectId === project.id
            ? 'border-primary bg-primary/5'
            : 'border-default bg-elevated hover:border-primary/30'"
          @click="ingestionStore.setActiveProject(project.id); activeStep = 1"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2 min-w-0">
              <UIcon name="lucide:folder" class="h-4 w-4 text-primary shrink-0" />
              <span class="text-sm font-medium truncate">{{ project.name }}</span>
            </div>
            <div class="flex items-center gap-1">
              <UButton
                icon="lucide:scan"
                size="xs"
                color="primary"
                variant="ghost"
                @click.stop="startScan(project.id)"
              />
              <UButton
                icon="lucide:trash-2"
                size="xs"
                color="error"
                variant="ghost"
                @click.stop="handleDeleteProject(project.id)"
              />
            </div>
          </div>
          <div class="mt-1.5 flex items-center gap-3 text-[11px] text-muted font-mono">
            <span class="truncate">{{ project.path }}</span>
          </div>
          <div class="mt-1 flex items-center gap-2 text-[10px]">
            <span class="rounded bg-default px-1.5 py-0.5 text-muted">{{ formatNumber(project.chunksCount) }} chunks</span>
            <span class="rounded bg-default px-1.5 py-0.5 text-muted">{{ project.status }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 右侧：工作流 (65%) -->
    <div class="flex flex-col flex-1 overflow-hidden">
      <!-- 步骤指示器 -->
      <div class="flex items-center gap-2 px-6 py-3 border-b border-default">
        <div
          v-for="(step, i) in ['路径校验', '类型筛选', '入库处理']"
          :key="i"
          class="flex items-center gap-2"
        >
          <div
            class="flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold"
            :class="(i + 1) <= activeStep ? 'bg-primary text-white' : 'bg-elevated text-muted'"
          >
            {{ i + 1 }}
          </div>
          <span class="text-xs" :class="(i + 1) <= activeStep ? 'text-default' : 'text-muted'">
            {{ step }}
          </span>
          <UIcon v-if="i < 2" name="lucide:chevron-right" class="h-3 w-3 text-muted" />
        </div>
        <div class="flex-1" />
        <UButton v-if="activeStep > 1 && progress?.status !== 'running'" size="xs" color="neutral" variant="ghost" @click="resetWorkflow">
          重新开始
        </UButton>
      </div>

      <!-- 步骤内容 -->
      <div class="flex-1 overflow-y-auto p-6">
        <!-- Step 1: 路径校验 -->
        <div v-if="activeStep === 1" class="flex flex-col items-center justify-center h-full text-center">
          <UIcon name="lucide:folder-search" class="h-12 w-12 text-muted mb-4" />
          <p class="text-sm text-muted">选择左侧项目后点击扫描图标开始</p>
          <p class="text-xs text-muted/60 mt-1">或创建新项目添加文档路径</p>
        </div>

        <!-- Step 2: 类型筛选 -->
        <div v-else-if="activeStep === 2 && scanResult" class="max-w-lg mx-auto space-y-4">
          <div class="text-center">
            <h3 class="text-sm font-medium text-default">文件类型筛选</h3>
            <p class="text-xs text-muted mt-1">共扫描到 {{ scanResult.totalFiles }} 个文件，选择要入库的类型</p>
          </div>
          <div class="grid grid-cols-2 gap-2">
            <label
              v-for="ft in scanResult.fileTypes"
              :key="ft.extension"
              class="flex items-center gap-2 rounded-lg border border-default bg-elevated p-3 text-sm cursor-pointer hover:border-primary/30"
            >
              <input v-model="ft.selected" type="checkbox" class="rounded" />
              <span class="font-mono text-default">{{ ft.extension }}</span>
              <span class="text-muted text-xs ml-auto">{{ ft.count }}</span>
            </label>
          </div>
          <div class="flex justify-center pt-2">
            <UButton
              color="primary"
              :disabled="scanResult.fileTypes.every(ft => !ft.selected)"
              @click="startIngestion"
            >
              开始入库
            </UButton>
          </div>
        </div>

        <!-- Step 3: 入库处理 -->
        <div v-else-if="activeStep === 3" class="space-y-4">
          <!-- 进度条 -->
          <div v-if="progress" class="space-y-2">
            <div class="flex items-center justify-between text-xs">
              <span class="text-muted">{{ progress.currentFile }}</span>
              <span class="font-mono text-default">{{ progress.progressPercentage }}% · ETA {{ eta }}</span>
            </div>
            <div class="relative h-2 rounded-full bg-default overflow-hidden">
              <div
                class="h-full bg-primary rounded-full transition-all duration-300 relative overflow-hidden"
                :style="{ width: `${progress.progressPercentage}%` }"
              >
                <div class="shimmer absolute inset-0" />
              </div>
            </div>
            <div class="flex items-center justify-between text-[10px] text-muted font-mono">
              <span>{{ progress.current }} / {{ progress.total }}</span>
              <span>{{ progress.status }}</span>
            </div>
          </div>
          <div v-else class="flex items-center gap-2 text-sm text-muted justify-center py-4">
            <UIcon name="lucide:loader-2" class="h-4 w-4 animate-spin" />
            正在启动入库...
          </div>

          <!-- 终端风格日志 -->
          <div class="terminal-log rounded-xl border border-default p-3 h-80 overflow-y-auto" ref="logContainerRef">
            <div v-for="(line, i) in logLines" :key="i" class="whitespace-pre-wrap">
              {{ line }}
            </div>
            <div v-if="progress?.status === 'running'" class="typing-cursor" />
          </div>

          <!-- 操作按钮 -->
          <div v-if="progress?.status === 'running'" class="flex justify-center">
            <UButton color="error" variant="outline" icon="lucide:x" @click="handleCancel">
              取消入库
            </UButton>
          </div>
        </div>
      </div>
    </div>

    <!-- 创建项目模态框 -->
    <UModal v-model:open="showCreateModal" title="创建项目">
      <template #body>
        <div class="space-y-4 p-2">
          <UFormField label="项目名称">
            <UInput v-model="newProject.name" placeholder="my-project" />
          </UFormField>
          <UFormField label="文档路径">
            <UInput v-model="newProject.path" placeholder="/path/to/docs" class="font-mono text-xs" />
          </UFormField>
          <UFormField label="文件匹配">
            <UInput v-model="newProject.filePatterns" placeholder="*.md,*.txt,*.java" class="font-mono text-xs" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <div class="flex justify-end gap-2">
          <UButton color="neutral" variant="ghost" @click="showCreateModal = false">取消</UButton>
          <UButton color="primary" :disabled="!newProject.name || !newProject.path" @click="handleCreateProject">创建</UButton>
        </div>
      </template>
    </UModal>
  </div>
</template>
