<template>
  <div>
    <div class="page-head">
      <div>
        <h1>片源接口</h1>
        <p>这里维护的接口会下发给所有登录用户（分组「官方推荐」），客户端失败时自动退回本地源</p>
      </div>
      <div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openEdit(null)">新增接口</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="rows" v-loading="loading" size="default" empty-text="还没有配置片源接口">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <div class="name-line">
              <span>{{ row.name }}</span>
              <span v-if="row.isDefault" class="pill pill-brand">默认</span>
            </div>
            <div class="muted mini">权重 {{ row.weight }}</div>
          </template>
        </el-table-column>

        <el-table-column label="地址 / 连通性" min-width="260">
          <template #default="{ row }">
            <el-tooltip :content="row.url" placement="top">
              <div class="mono ellipsis">{{ row.url }}</div>
            </el-tooltip>
            <div class="mini">
              <span v-if="row.lastCheckOk === null || row.lastCheckOk === undefined" class="muted">未测试</span>
              <span v-else-if="row.lastCheckOk === 1" class="ok-text">最近测试：正常</span>
              <span v-else class="danger-text">最近测试：失败</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="启用" width="76">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v) => toggle(row, v)" />
          </template>
        </el-table-column>

        <el-table-column label="备注" min-width="100">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="test(row)">测试</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑接口' : '新增接口'" width="560px">
      <el-form label-width="90px" label-position="left">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如 官方源 A" />
        </el-form-item>
        <el-form-item label="接口地址">
          <el-input v-model="form.url" type="textarea" :rows="3" placeholder="http:// 或 https:// 开头的配置地址" />
        </el-form-item>
        <el-form-item label="权重">
          <el-input-number v-model="form.weight" :min="0" :max="9999" controls-position="right" />
          <span class="hint-inline">越大越靠前</span>
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="hint-inline">新用户首次登录时自动选中</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="200" placeholder="失效原因、维护人等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testVisible" title="连通性测试" width="480px">
      <div v-if="testResult" class="test-result">
        <div class="kv"><span class="k">结果</span>
          <span :class="testResult.ok ? 'ok-text' : 'danger-text'">{{ testResult.ok ? '可正常解析' : '无法使用' }}</span>
        </div>
        <div class="kv"><span class="k">耗时</span><span>{{ testResult.ms }} ms</span></div>
        <div v-if="testResult.status" class="kv"><span class="k">状态码</span><span>{{ testResult.status }}</span></div>
        <div v-if="testResult.error" class="kv"><span class="k">错误</span><span class="mono">{{ testResult.error }}</span></div>
        <div v-if="testResult.preview" class="kv"><span class="k">响应片段</span>
          <pre class="preview mono">{{ testResult.preview }}</pre>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="testVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import { api } from '../api'

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const testingId = ref(0)

const dialogVisible = ref(false)
const editing = ref(null)
const form = reactive({ name: '', url: '', weight: 100, isDefault: false, enabled: true, remark: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await api.sources()
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  editing.value = row
  form.name = row?.name || ''
  form.url = row?.url || ''
  form.weight = row?.weight ?? 100
  form.isDefault = !!row?.isDefault
  form.enabled = row ? !!row.enabled : true
  form.remark = row?.remark || ''
  dialogVisible.value = true
}

async function save() {
  if (!form.name || !form.url) {
    ElMessage.warning('名称和地址不能为空')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateSource(editing.value.id, { ...form })
    } else {
      await api.createSource({ ...form })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row, v) {
  await api.updateSource(row.id, { enabled: v })
  row.enabled = v
}

async function remove(row) {
  await ElMessageBox.confirm(`删除「${row.name}」后，已在使用它的用户不会立刻失效，但下次拉取将不再下发。`, '删除接口', {
    type: 'warning'
  })
  await api.deleteSource(row.id)
  ElMessage.success('已删除')
  load()
}

const testVisible = ref(false)
const testResult = ref(null)

async function test(row) {
  testingId.value = row.id
  try {
    testResult.value = null
    testVisible.value = true
    testResult.value = await api.testSource(row.id)
    load()
  } finally {
    testingId.value = 0
  }
}

onMounted(load)
</script>

<style scoped>
.name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ellipsis {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini {
  font-size: 11px;
  line-height: 1.5;
}

.hint-inline {
  font-size: 12px;
  color: var(--ink-400);
  margin-left: 10px;
}

.test-result .kv {
  display: flex;
  gap: 12px;
  font-size: 13px;
  margin-bottom: 10px;
}

.test-result .k {
  width: 76px;
  flex: 0 0 76px;
  color: var(--ink-400);
}

.preview {
  margin: 0;
  padding: 10px;
  background: #f7f8fb;
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 140px;
  overflow: auto;
}
</style>
