import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { ProjectConfig, ScanResult, IngestionProgress } from '@/types/ingestion'
import { api } from '@/utils/api'

export const useIngestionStore = defineStore('ingestion', () => {
  const projects = ref<ProjectConfig[]>([])
  const activeProjectId = ref<string | null>(null)
  const scanResult = ref<ScanResult | null>(null)
  const progress = ref<IngestionProgress | null>(null)
  const isScanning = ref(false)
  const isIngesting = ref(false)

  const activeProject = computed(() =>
    projects.value.find(p => p.id === activeProjectId.value) ?? null,
  )

  async function fetchProjects() {
    projects.value = await api<ProjectConfig[]>('/project-configs')
  }

  async function createProject(data: Partial<ProjectConfig>) {
    const project = await api<ProjectConfig>('/project-configs', {
      method: 'POST',
      body: data,
    })
    projects.value.push(project)
    return project
  }

  async function deleteProject(id: string) {
    await api(`/project-configs/${id}`, { method: 'DELETE' })
    projects.value = projects.value.filter(p => p.id !== id)
    if (activeProjectId.value === id) activeProjectId.value = null
  }

  async function scanProject(id: string, filePatterns: string) {
    isScanning.value = true
    try {
      const result = await api<ScanResult>('/ingestion/scan', {
        method: 'POST',
        body: { projectId: id, filePatterns },
      })
      scanResult.value = result
      return result
    } finally {
      isScanning.value = false
    }
  }

  function resetProgress() {
    progress.value = null
  }

  function setActiveProject(id: string | null) {
    activeProjectId.value = id
  }

  return {
    projects,
    activeProjectId,
    activeProject,
    scanResult,
    progress,
    isScanning,
    isIngesting,
    fetchProjects,
    createProject,
    deleteProject,
    scanProject,
    resetProgress,
    setActiveProject,
  }
})
