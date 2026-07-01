import http from './http'
import type { Project, ProjectInfo, ScanResult } from './types/ingestion'

/** 获取项目列表 */
export async function getProjects(): Promise<Project[]> {
  const { data } = await http.get<Project[]>('/api/project-configs')
  return data
}

/** 获取项目详情 */
export async function getProject(id: string): Promise<Project> {
  const { data } = await http.get<Project>(`/api/project-configs/${id}`)
  return data
}

/** 创建项目 */
export async function createProject(project: { name: string; path: string }): Promise<Project> {
  const { data } = await http.post<Project>('/api/project-configs', project)
  return data
}

/** 删除项目 */
export async function deleteProject(id: string): Promise<void> {
  await http.delete(`/api/project-configs/${id}`)
}

/** 扫描文件类型 */
export async function scanFiles(projects: ProjectInfo[]): Promise<ScanResult> {
  const { data } = await http.post<ScanResult>('/api/ingestion/scan', { projects })
  return data
}

/** 获取项目 chunk 数量 */
export async function getChunkCount(projectName: string): Promise<number> {
  const { data } = await http.get<{ count: number }>(`/api/ingestion/chunks/${encodeURIComponent(projectName)}/count`)
  return data.count
}

/** 删除项目 chunks */
export async function deleteChunks(projectName: string): Promise<{ deleted: number }> {
  const { data } = await http.delete<{ deleted: number }>(`/api/ingestion/chunks/${encodeURIComponent(projectName)}`)
  return data
}

/** 清空所有 chunks */
export async function clearAllChunks(): Promise<{ deleted: number }> {
  const { data } = await http.delete<{ deleted: number }>('/api/ingestion/chunks')
  return data
}

/**
 * 创建入库 SSE 流
 * 返回 Response，调用方自行解析 data: 行
 */
export function createIngestStream(projects: ProjectInfo[], exts: string[]): Promise<Response> {
  return fetch('/api/ingestion/process', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projects, exts }),
  })
}
