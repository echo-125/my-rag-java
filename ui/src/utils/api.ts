import { ofetch } from 'ofetch'

export const api = ofetch.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
  retry: 2,
  timeout: 30000,
  async onResponseError({ response }) {
    let msg = `请求失败 (${response.status})`
    try {
      const body = await response.clone().json()
      if (body && typeof body === 'object' && 'message' in body) {
        msg = (body as { message: string }).message
      }
    } catch {
      // 非 JSON 响应，使用默认消息
    }
    throw new Error(msg)
  },
})

export function getApiBase(): string {
  return '/api'
}
