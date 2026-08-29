import request from './request'

/**
 * 商品模块 API（与 Knife4j 中 ProductController 定义完全一致）
 */

// 新增商品 POST /product/add  返回商品ID
export function addProduct(data) {
  return request.post('/product/add', data)
}

// 修改商品 PUT /product/update
export function updateProduct(data) {
  return request.put('/product/update', data)
}

// 删除商品 DELETE /product/delete?id=xxx
export function deleteProduct(id) {
  return request.delete('/product/delete', { params: { id } })
}

// 商品上下架 PUT /product/status  body: {id, status}
export function updateProductStatus(data) {
  return request.put('/product/status', data)
}

// 商品详情 GET /product/detail?id=xxx
export function getProductDetail(id) {
  return request.get('/product/detail', { params: { id } })
}

// 商品列表 GET /product/list?keyword=&category=&status=
export function listProducts(params) {
  return request.get('/product/list', { params })
}

// 库存查询 GET /product/stock?id=xxx
export function getProductStock(id) {
  return request.get('/product/stock', { params: { id } })
}
