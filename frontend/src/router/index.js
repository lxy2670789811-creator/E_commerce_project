import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layout/Layout.vue'

// 后台路由：不做登录，直接进入首页
const routes = [
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/dashboard/Dashboard.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'product',
        name: 'Product',
        component: () => import('../views/product/ProductList.vue'),
        meta: { title: '商品管理' }
      },
      {
        path: 'order',
        name: 'Order',
        component: () => import('../views/order/OrderList.vue'),
        meta: { title: '订单管理' }
      },
      {
        path: 'address',
        name: 'Address',
        component: () => import('../views/user/AddressList.vue'),
        meta: { title: '收货地址' }
      },
      {
        path: 'ai',
        name: 'AiAfterSupport',
        component: () => import('../views/ai/AfterSupport.vue'),
        meta: { title: 'AI 售后分析' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
