<template>
  <div>
    <!-- 搜索区 -->
    <el-card shadow="never" class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="商品名称" clearable style="width: 180px" @keyup.enter="loadList" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="query.category" placeholder="分类" clearable style="width: 140px" @keyup.enter="loadList" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="上架" :value="1" />
            <el-option label="下架" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表区 -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="total">共 {{ total }} 件商品</span>
        <el-button type="primary" @click="openDialog()">新增商品</el-button>
      </div>

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div class="product-cell">
              <el-image
                v-if="row.imageUrl"
                :src="row.imageUrl"
                fit="cover"
                class="product-img"
                :preview-src-list="[row.imageUrl]"
                preview-teleported
              />
              <div class="product-info">
                <div class="product-name">{{ row.name }}</div>
                <div class="product-desc">{{ row.category || '未分类' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="110">
          <template #default="{ row }">￥{{ row.price }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
            <el-button link type="info" @click="showStock(row)">库存</el-button>
            <el-button link type="danger" @click="removeProduct(row)">删除</el-button>
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

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑商品' : '新增商品'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="0" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="例如：数码配件" />
        </el-form-item>
        <el-form-item label="图片URL">
          <el-input v-model="form.imageUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="商品描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 库存查询结果 -->
    <el-dialog v-model="stockDialog.visible" title="库存查询" width="420px">
      <el-descriptions v-if="stockDialog.data" :column="1" border>
        <el-descriptions-item label="商品ID">{{ stockDialog.data.productId }}</el-descriptions-item>
        <el-descriptions-item label="商品名称">{{ stockDialog.data.productName }}</el-descriptions-item>
        <el-descriptions-item label="当前库存">{{ stockDialog.data.stock }}</el-descriptions-item>
        <el-descriptions-item label="是否有货">
          <el-tag :type="stockDialog.data.hasStock ? 'success' : 'danger'">
            {{ stockDialog.data.hasStock ? '有货' : '无货' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上架状态">{{ stockDialog.data.statusText }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listProducts,
  addProduct,
  updateProduct,
  deleteProduct,
  updateProductStatus,
  getProductStock
} from '../../api/product'

// ---------- 查询与列表 ----------
const query = reactive({ keyword: '', category: '', status: null })
const list = ref([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    // 清空字符串参数，避免传空值
    const params = { page: page.value, pageSize }
    if (query.keyword) params.keyword = query.keyword
    if (query.category) params.category = query.category
    if (query.status !== null && query.status !== '') params.status = query.status
    const data = await listProducts(params)
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

function resetQuery() {
  query.keyword = ''
  query.category = ''
  query.status = null
  search()
}

// ---------- 新增 / 编辑 ----------
const formRef = ref()
const dialog = reactive({ visible: false, isEdit: false, submitting: false })
const emptyForm = () => ({
  id: null,
  name: '',
  price: 0.01,
  stock: 0,
  status: 1,
  category: '',
  imageUrl: '',
  description: ''
})
const form = reactive(emptyForm())

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

function openDialog(row) {
  dialog.isEdit = !!row
  Object.assign(form, emptyForm(), row || {})
  page.value = 1
  dialog.visible = true
}

async function submitForm() {
  await formRef.value.validate()
  dialog.submitting = true
  try {
    if (dialog.isEdit) {
      await updateProduct({ ...form })
      ElMessage.success('修改成功')
    } else {
      const id = await addProduct({ ...form })
      ElMessage.success(`新增成功，商品ID：${id}`)
    }
    dialog.visible = false
    loadList()
  } finally {
    dialog.submitting = false
  }
}

// ---------- 上下架 / 删除 ----------
async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  await updateProductStatus({ id: row.id, status: next })
  ElMessage.success(next === 1 ? '已上架' : '已下架')
  loadList()
}

async function removeProduct(row) {
  await ElMessageBox.confirm(`确定删除商品「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteProduct(row.id)
  ElMessage.success('删除成功')
  loadList()
}

// ---------- 库存查询 ----------
const stockDialog = reactive({ visible: false, data: null })

async function showStock(row) {
  stockDialog.data = await getProductStock(row.id)
  stockDialog.visible = true
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

.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.total {
  color: #909399;
  font-size: 13px;
}

.product-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.product-img {
  width: 48px;
  height: 48px;
  border-radius: 4px;
  flex-shrink: 0;
}

.product-name {
  font-weight: 500;
}

.product-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
