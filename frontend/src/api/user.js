import request from './request'

/**
 * 用户模块 API（与 Knife4j 中 UserController 定义完全一致）
 */

// 登录 POST /user/login  body: {username, password}  返回 {userId, username, nickname, token(JWT)}
// 成功后建议把 token 存入 localStorage（key 见 request.js 的 TOKEN_KEY），后续请求会自动附带
export function login(data) {
  return request.post('/user/login', data)
}

// 查询地址列表 GET /user/address/list?userId=xxx
export function listAddress(userId) {
  return request.get('/user/address/list', { params: { userId } })
}

// 新增收货地址 POST /user/address/add  返回地址ID
export function addAddress(data) {
  return request.post('/user/address/add', data)
}
