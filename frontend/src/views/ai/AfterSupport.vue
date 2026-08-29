<template>
  <div>
    <!-- 输入区 -->
    <el-card shadow="never" class="form-card">
      <template #header>
        <span>提交售后反馈，DeepSeek 自动分析问题分类、情绪与处理建议</span>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="订单ID" prop="orderId">
          <el-input-number v-model="form.orderId" :min="1" :step="1" style="width: 220px" />
        </el-form-item>
        <el-form-item label="用户ID" prop="userId">
          <el-input-number v-model="form.userId" :min="1" :step="1" style="width: 220px" />
        </el-form-item>
        <el-form-item label="问题描述" prop="userInput">
          <el-input
            v-model="form.userInput"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="例如：收到商品破损了，包装完好但产品屏幕有裂痕，要求退款"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="analyzing" @click="submit">
            {{ analyzing ? 'AI 分析中（最长30秒）...' : '开始智能分析' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 结果区 -->
    <el-card v-if="result" shadow="never">
      <template #header>
        <div class="result-header">
          <span>分析结果</span>
          <el-tag :type="result.aiStatus === 1 ? 'success' : 'warning'">{{ result.aiStatusText }}</el-tag>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号" :span="2">{{ result.orderNo || result.orderId }}</el-descriptions-item>
        <el-descriptions-item label="问题分类">
          <el-tag type="danger" effect="plain">{{ result.problemType || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="情绪判断">
          <el-tag :type="emotionTagType" effect="plain">{{ result.emotion || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="建议处理方案" :span="2">{{ result.suggestion || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="result.failReason" label="失败原因" :span="2">
          <span class="fail-reason">{{ result.failReason }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="result.aiStatus !== 1"
        class="fallback-tip"
        type="info"
        :closable="false"
        title="AI 调用失败已自动降级为待人工审核，可稍后重试"
      />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { analyzeAfterSupport } from '../../api/ai'

const route = useRoute()

const formRef = ref()
const analyzing = ref(false)
const result = ref(null)

const form = reactive({ orderId: 1, userId: 1, userInput: '' })
const rules = {
  orderId: [{ required: true, message: '必填', trigger: 'blur' }],
  userId: [{ required: true, message: '必填', trigger: 'blur' }],
  userInput: [{ required: true, message: '请输入问题描述', trigger: 'blur' }]
}

// 情绪标签颜色
const emotionTagType = computed(() => {
  const e = result.value?.emotion || ''
  if (e.includes('负面') || e.includes('愤怒') || e.includes('不满')) return 'danger'
  if (e.includes('中性') || e.includes('一般')) return 'info'
  if (e.includes('正面') || e.includes('满意') || e.includes('高兴')) return 'success'
  return 'info'
})

async function submit() {
  await formRef.value.validate()
  analyzing.value = true
  result.value = null
  try {
    result.value = await analyzeAfterSupport({ ...form })
  } finally {
    analyzing.value = false
  }
}

// 支持从订单页跳转携带参数
onMounted(() => {
  if (route.query.orderId) form.orderId = Number(route.query.orderId)
  if (route.query.userId) form.userId = Number(route.query.userId)
})
</script>

<style scoped>
.form-card {
  margin-bottom: 16px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.fail-reason {
  color: #f56c6c;
}

.fallback-tip {
  margin-top: 14px;
}
</style>
