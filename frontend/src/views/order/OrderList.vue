<template>
  <div>
    <!-- 查询区 -->
    <el-card shadow="never" class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="用户ID" required>
          <el-input-number v-model="query.userId" :min="1" :step="1" placeholder="例如 1" style="width: 140px" />
        </el-form-item>
        <el-form-item label="订单状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="(text, code) in statusMap" :key="code" :label="text" :value="Number(code)" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!query.userId" @click="search">查询</el-button>
          <el-button type="success" @click="openCreateDialog">创建订单</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表区 -->
    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="orderNo" label="订单号" min-width="190" show-overflow-tooltip />
        <el-table-column prop="productName" label="商品" min-width="160" show-overflow-tooltip />
        <el-table-column label="单价" width="100">
          <template #default="{ row }">￥{{ row.productPrice }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="70" />
        <el-table-column label="总金额" width="110">
          <template #default="{ row }">
            <span class="amount">￥{{ row.totalAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType[row.status]">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
            <el-button v-if="row.status === 0" link type="success" @click="mockPay(row)">模拟支付</el-button>
            <el-button v-if="row.status === 1" link type="primary" @click="doShip(row)">发货</el-button>
            <el-button v-if="row.status === 2" link type="success" @click="doFinish(row)">完成</el-button>
            <el-button
              v-if="row.status === 0 || row.status === 1"
              link
              type="danger"
              @click="openCancelDialog(row)"
            >取消</el-button>
            <el-button link type="warning" @click="goAiAnalysis(row)">AI售后</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 服务端分页 -->
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next, jumper"
        class="pager"
        @current-change="loadList"
      />
    </el-card>

    <!-- 创建订单对话框 -->
    <el-dialog v-model="createDialog.visible" title="创建订单" width="460px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="用户ID" prop="userId">
          <el-input-number v-model="createForm.userId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="商品ID" prop="productId">
          <el-input-number v-model="createForm.productId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="购买数量" prop="quantity">
          <el-input-number v-model="createForm.quantity" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="地址ID" prop="addressId">
          <el-input-number v-model="createForm.addressId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.submitting" @click="submitCreate">下单</el-button>
      </template>
    </el-dialog>

    <!-- 订单详情抽屉 -->
    <el-drawer v-model="detailDrawer.visible" title="订单详情" size="480px">
      <el-descriptions v-if="detailDrawer.data" :column="1" border>
        <el-descriptions-item label="订单ID">{{ detailDrawer.data.id }}</el-descriptions-item>
        <el-descriptions-item label="订单号">{{ detailDrawer.data.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="商品名称">{{ detailDrawer.data.productName }}</el-descriptions-item>
        <el-descriptions-item label="商品单价">￥{{ detailDrawer.data.productPrice }}</el-descriptions-item>
        <el-descriptions-item label="购买数量">{{ detailDrawer.data.quantity }}</el-descriptions-item>
        <el-descriptions-item label="总金额">￥{{ detailDrawer.data.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="订单状态">
          <el-tag :type="statusTagType[detailDrawer.data.status]">{{ detailDrawer.data.statusText }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="收货地址">{{ detailDrawer.data.addressSnapshot || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detailDrawer.data.createTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="支付时间">{{ detailDrawer.data.payTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发货时间">{{ detailDrawer.data.shipTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ detailDrawer.data.finishTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="取消时间">{{ detailDrawer.data.cancelTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="取消原因">{{ detailDrawer.data.cancelReason || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>

    <!-- 取消订单对话框 -->
    <el-dialog v-model="cancelDialog.visible" title="取消订单" width="460px">
      <el-form label-width="90px">
        <el-form-item label="订单号">
          <span>{{ cancelDialog.row?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="取消原因" required>
          <el-input v-model="cancelDialog.reason" type="textarea" :rows="2" placeholder="例如：不想要了" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialog.visible = false">再想想</el-button>
        <el-button type="danger" :loading="cancelDialog.submitting" @click="submitCancel">确认取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listOrders, getOrderDetail, createOrder, cancelOrder, payCallback, shipOrder, finishOrder, getOrderToken } from '../../api/order'

const router = useRouter()

// 订单状态映射（与后端 OrderStatusEnum 一致）
const statusMap = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已完成', 4: '已取消' }
const statusTagType = { 0: 'warning', 1: 'primary', 2: '', 3: 'success', 4: 'info' }

// ---------- 列表查询 ----------
const query = reactive({ userId: 1, status: null })
const list = ref([])
const loading = ref(false)

async function loadList() {
  if (!query.userId) {
    ElMessage.warning('请输入用户ID')
    return
  }
  loading.value = true
  try {
    const params = { userId: query.userId, page: page.value, pageSize }
    if (query.status !== null && query.status !== '') params.status = query.status
    const data = await listOrders(params)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

// ---------- 服务端分页 ----------
const page = ref(1)
const pageSize = 8
const total = ref(0)

function search() {
  page.value = 1
  loadList()
}

// ---------- 创建订单 ----------
const createFormRef = ref()
const createDialog = reactive({ visible: false, submitting: false })
const createForm = reactive({ userId: 1, productId: 1, quantity: 1, addressId: 1 })
const createRules = {
  userId: [{ required: true, message: '必填', trigger: 'blur' }],
  productId: [{ required: true, message: '必填', trigger: 'blur' }],
  quantity: [{ required: true, message: '必填', trigger: 'blur' }],
  addressId: [{ required: true, message: '必填', trigger: 'blur' }]
}

function openCreateDialog() {
  Object.assign(createForm, { userId: query.userId || 1, productId: 1, quantity: 1, addressId: 1 })
  createDialog.visible = true
}

async function submitCreate() {
  await createFormRef.value.validate()
  createDialog.submitting = true
  try {
    // 幂等：先领取一次性下单凭证，再随订单一起提交。
    // 同一凭证第二次提交会被后端拒绝，防止双击/前端超时重试导致的重复下单。
    const token = await getOrderToken({ userId: createForm.userId, productId: createForm.productId })
    const orderNo = await createOrder({ ...createForm, token })
    ElMessage.success(`下单成功，订单号：${orderNo}`)
    createDialog.visible = false
    query.userId = createForm.userId
    loadList()
  } finally {
    createDialog.submitting = false
  }
}

// ---------- 详情 ----------
const detailDrawer = reactive({ visible: false, data: null })

async function showDetail(row) {
  detailDrawer.data = await getOrderDetail({ orderId: row.id, userId: row.userId })
  detailDrawer.visible = true
}

// ---------- 取消订单 ----------
const cancelDialog = reactive({ visible: false, submitting: false, row: null, reason: '' })

function openCancelDialog(row) {
  cancelDialog.row = row
  cancelDialog.reason = ''
  cancelDialog.visible = true
}

async function submitCancel() {
  if (!cancelDialog.reason.trim()) {
    ElMessage.warning('请填写取消原因')
    return
  }
  cancelDialog.submitting = true
  try {
    await cancelOrder({
      orderId: cancelDialog.row.id,
      userId: cancelDialog.row.userId,
      cancelReason: cancelDialog.reason
    })
    ElMessage.success('订单已取消，库存已回滚')
    cancelDialog.visible = false
    loadList()
  } finally {
    cancelDialog.submitting = false
  }
}

// ---------- 支付 / 发货 / 完成（模拟状态流转） ----------
async function mockPay(row) {
  await payCallback({ orderNo: row.orderNo })
  ElMessage.success('支付成功')
  loadList()
}

async function doShip(row) {
  await shipOrder({ orderId: row.id })
  ElMessage.success('已发货')
  loadList()
}

async function doFinish(row) {
  await finishOrder({ orderId: row.id })
  ElMessage.success('已完成')
  loadList()
}

// ---------- 跳转 AI 售后分析 ----------
function goAiAnalysis(row) {
  router.push({ path: '/ai', query: { orderId: row.id, userId: row.userId } })
}

onMounted(loadList)
</script>

<style scoped>
.search-card {
  margin-bottom: 16px;
}

.search-card :deep(.el-form-item) {
  margin-bottom: 0;
}

.amount {
  color: #f56c6c;
  font-weight: 600;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
