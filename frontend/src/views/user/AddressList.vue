<template>
  <div>
    <el-card shadow="never" class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="用户ID" required>
          <el-input-number v-model="userId" :min="1" :step="1" style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadList">查询</el-button>
          <el-button type="success" @click="openDialog">新增地址</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="receiver" label="收货人" width="100" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="fullAddress" label="完整地址" min-width="240" show-overflow-tooltip />
        <el-table-column label="是否默认" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success">默认</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
      </el-table>
    </el-card>

    <!-- 新增地址对话框 -->
    <el-dialog v-model="dialog.visible" title="新增收货地址" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="用户ID" prop="userId">
          <el-input-number v-model="form.userId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收货人" prop="receiver">
          <el-input v-model="form.receiver" placeholder="收货人姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="收货人手机号" />
        </el-form-item>
        <el-form-item label="省份" prop="province">
          <el-input v-model="form.province" placeholder="例如：广东省" />
        </el-form-item>
        <el-form-item label="城市" prop="city">
          <el-input v-model="form.city" placeholder="例如：深圳市" />
        </el-form-item>
        <el-form-item label="区县" prop="district">
          <el-input v-model="form.district" placeholder="例如：南山区" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detail">
          <el-input v-model="form.detail" type="textarea" :rows="2" placeholder="街道门牌信息" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAddress, addAddress } from '../../api/user'

const userId = ref(1)
const list = ref([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    list.value = await listAddress(userId.value)
  } finally {
    loading.value = false
  }
}

// ---------- 新增 ----------
const formRef = ref()
const dialog = reactive({ visible: false, submitting: false })
const emptyForm = () => ({ userId: 1, receiver: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: 0 })
const form = reactive(emptyForm())
const rules = {
  userId: [{ required: true, message: '必填', trigger: 'blur' }],
  receiver: [{ required: true, message: '请输入收货人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  district: [{ required: true, message: '请输入区县', trigger: 'blur' }],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }]
}

function openDialog() {
  Object.assign(form, emptyForm(), { userId: userId.value })
  dialog.visible = true
}

async function submitForm() {
  await formRef.value.validate()
  dialog.submitting = true
  try {
    const id = await addAddress({ ...form })
    ElMessage.success(`新增成功，地址ID：${id}`)
    dialog.visible = false
    loadList()
  } finally {
    dialog.submitting = false
  }
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
</style>
