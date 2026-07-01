/** 反馈请求（/api/feedback） */
export interface FeedbackRequest {
  qaHistoryId: string
  rating: 1 | -1
}

/** QA 保存请求（/api/chat/save） */
export interface QaSaveRequest {
  question: string
  answer: string
  modelName: string
}
