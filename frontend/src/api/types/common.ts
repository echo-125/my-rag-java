/** 后端统一错误响应格式 */
export interface ApiError {
  error: string
  message?: string
  status?: number
}

/** SSE 流中的错误 */
export interface StreamError {
  error: string
}
