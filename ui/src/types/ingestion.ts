export interface ProjectConfig {
  id: string
  name: string
  path: string
  filePatterns: string
  status: 'idle' | 'scanning' | 'ingesting' | 'completed' | 'error'
  chunksCount: number
  createdAt: string
  updatedAt: string
}

export interface ScanResult {
  totalFiles: number
  fileTypes: FileTypeStat[]
}

export interface FileTypeStat {
  extension: string
  count: number
  selected: boolean
}

export interface IngestionProgress {
  current: number
  total: number
  progressPercentage: number
  currentFile: string
  status: 'running' | 'completed' | 'error' | 'cancelled'
  message: string
  elapsedTimeMs: number
}
