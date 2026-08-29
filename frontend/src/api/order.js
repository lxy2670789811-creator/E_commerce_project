import request from './request'

/**
 * 订单模块 API（与 Knife4j 中 OrderController 定义完全一致）
 */

// 创建订单 POST /order/create  body: {userId, productId, quantity, addressId}  返回订单号
export function createOrder(data) {
  return request.post('/order/create', data)
}

// 订单列表 GET /order/list?userId=xxx&status=
export function listOrders(params) {
  return request.get('/order/list', { params })
}

// 订单详情 GET /order/detail?orderId=xxx&userId=xxx
export function getOrderDetail(params) {
  return request.get('/order/detail', { params })
}

// 取消订单 POST /order/cancel  body: {orderId, userId, cancelReason}
export function cancelOrder(data) {
  return request.post('/order/cancel', data)
}

// 模拟支付回调 POST /order/pay-callback  body: {orderNo}
export function payCallback(data) {
  return request.post('/order/pay-callback', data)
}

// 发货（管理端） POST /order/ship  body: {orderId}
export function shipOrder(data) {
  return request.post('/order/ship', data)
}

// 完成订单（管理端） POST /order/finish  body: {orderId}
export function finishOrder(data) {
  return request.post('/order/finish', data)
}
