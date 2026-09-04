import request from './request'

/**
 * 订单模块 API（与 Knife4j 中 OrderController 定义完全一致）
 */

// 获取下单凭证（幂等用）GET /order/token?userId=xxx&productId=xxx  返回一次性 token
// 进入下单页时领取，提交订单时放在 body 里带上；提交后即失效，用同一张凭证重复提交会被拒绝
export function getOrderToken(params) {
  return request.get('/order/token', { params })
}

// 创建订单 POST /order/create  body: {userId, productId, quantity, addressId, token}  返回订单号
// 注意：token 为一次性凭证，见 getOrderToken
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
