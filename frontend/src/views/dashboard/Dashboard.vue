<template>
  <div>
    <el-row :gutter="16">
      <!-- 欢迎卡片 -->
      <el-col :span="24">
        <el-card shadow="never" class="welcome-card">
          <h2>欢迎使用电商后台管理系统</h2>
          <p>后端：Spring Boot 3 + MyBatis-Plus + Redis + Redisson + RocketMQ + DeepSeek AI</p>
          <p>前端：Vue 3 + Vite + Element Plus + Axios</p>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="entry-row">
      <el-col :span="6" v-for="item in entries" :key="item.path">
        <el-card shadow="hover" class="entry-card" @click="router.push(item.path)">
          <div class="entry-body">
            <el-icon :size="40" :color="item.color">
              <component :is="item.icon" />
            </el-icon>
            <div class="entry-text">
              <div class="entry-title">{{ item.title }}</div>
              <div class="entry-desc">{{ item.desc }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Goods, Tickets, Location, MagicStick } from '@element-plus/icons-vue'

const router = useRouter()

const entries = [
  { path: '/product', title: '商品管理', desc: '增删改查、上下架、库存', icon: Goods, color: '#409eff' },
  { path: '/order', title: '订单管理', desc: '创建、详情、取消订单', icon: Tickets, color: '#67c23a' },
  { path: '/address', title: '收货地址', desc: '地址查询与新增', icon: Location, color: '#e6a23c' },
  { path: '/ai', title: 'AI 售后分析', desc: 'DeepSeek 智能售后', icon: MagicStick, color: '#f56c6c' }
]
</script>

<style scoped>
.welcome-card h2 {
  margin: 0 0 12px;
}

.welcome-card p {
  margin: 4px 0;
  color: #606266;
}

.entry-row {
  margin-top: 16px;
}

.entry-card {
  cursor: pointer;
}

.entry-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.entry-title {
  font-size: 16px;
  font-weight: 600;
}

.entry-desc {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
</style>
