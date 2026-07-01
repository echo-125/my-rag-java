import http from './http'
import type { FeedbackRequest } from './types/feedback'

/** 提交反馈 */
export async function submitFeedback(request: FeedbackRequest): Promise<void> {
  await http.post('/api/feedback', request)
}
