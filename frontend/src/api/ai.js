import request from './request'

/**
 * AI 售后分析 API（与 Knife4j 中 AiAfterSupportController 定义完全一致）
 */

// 智能售后分析 POST /ai/after-support/analyze  body: {orderId, userId, userInput}
export function analyzeAfterSupport(data) {
  return request.post('/ai/after-support/analyze', data)
}
