import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * Axios 统一封装
 * 1. 统一请求前缀：baseURL = /api（开发环境由 Vite 代理转发到 http://localhost:8080）
 * 2. 统一响应解析：后端返回 Result<T> 格式 { code, message, data, timestamp }
 *    - code === 0 表示成功，直接把 data 返回给调用方
 *    - code !== 0 统一弹出错误提示
 * 3. 统一网络异常处理
 */
const service = axios.create({
  baseURL: '/api',
  // AI 售后分析接口耗时较长（后端 30 秒超时），这里给足时间
  timeout: 60000
})

// 响应拦截器：统一处理 Result<T>
service.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端 Result 约定：code = 0 为成功
    if (res.code === 0) {
      // 调用方拿到的直接就是 data，例如 const productId = await addProduct(dto)
      return res.data
    }
    // 业务失败：统一弹错误提示
    ElMessage.error(res.message || '操作失败')
    return Promise.reject(new Error(res.message || '操作失败'))
  },
  (error) => {
    // HTTP 层错误（超时、跨域、后端未启动等）
    let msg = '网络异常，请检查后端服务是否启动'
    if (error.code === 'ECONNABORTED') {
      msg = '请求超时，请稍后重试'
    } else if (error.response) {
      msg = `请求失败（HTTP ${error.response.status}）`
    }
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default service
